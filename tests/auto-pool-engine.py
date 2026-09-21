"""Native URLTest integration check using local mock SOCKS nodes only.
Usage: python tests/auto-pool-engine.py ENGINE DOTNET
No credentials, external probe traffic, TUN interface or admin rights required.
"""
import http.client, json, pathlib, socket, socketserver, subprocess, sys, threading, time
ENGINE, DOTNET = sys.argv[1:3]
ROOT = pathlib.Path(__file__).resolve().parents[1]
def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0)); return s.getsockname()[1]
def exact(s,n):
    out=b''
    while len(out)<n:
        part=s.recv(n-len(out))
        if not part: raise EOFError()
        out+=part
    return out
class Socks(socketserver.BaseRequestHandler):
    def handle(self):
        try:
            s=self.request;s.settimeout(3)
            version,n=exact(s,2);exact(s,n);s.sendall(b'\x05\x00')
            version,cmd,_,kind=exact(s,4)
            if kind==1: exact(s,4)
            elif kind==3: exact(s,exact(s,1)[0])
            elif kind==4: exact(s,16)
            exact(s,2)
            if not self.server.working: return
            s.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x50')
            data=b''
            while b'\r\n\r\n' not in data: data+=exact(s,1)
            time.sleep(self.server.delay)
            s.sendall(b'HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
        except (OSError,EOFError): pass
class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address=True;daemon_threads=True
servers=[]
for delay in [.35,.01]:
    s=Server(('127.0.0.1',0),Socks);s.delay=delay;s.working=True
    threading.Thread(target=s.serve_forever,daemon=True).start();servers.append(s)
controller, inbound=port(),port()
configs=[{'inbounds':[{'type':'http','tag':'test-in','listen':'127.0.0.1','listen_port':inbound}],
          'outbounds':[{'type':'socks','tag':'proxy','server':'127.0.0.1','server_port':s.server_address[1]}],
          'route':{'final':'proxy'}, 'log':{'disabled':True}} for s in servers]
build=subprocess.run([DOTNET,'run','--project',str(ROOT/'tests/windows-client/ClientChecks.csproj'),'--','--auto-fixture'],input=json.dumps(configs),text=True,capture_output=True,check=True)
config=json.loads(build.stdout.strip().splitlines()[-1]);config['experimental']={'clash_api':{'external_controller':f'127.0.0.1:{controller}','secret':'test-only-secret'}}
# Keep production's 30-second interval; the test explicitly requests a new sweep on failure.
config['outbounds'][0]['url']='http://127.0.0.1/generate_204'
def api(path):
    c=http.client.HTTPConnection('127.0.0.1',controller,timeout=5)
    try:
        c.request('GET',path,headers={'Authorization':'Bearer test-only-secret'})
        r=c.getresponse();body=r.read()
        if r.status!=200: raise RuntimeError((r.status,body))
        return json.loads(body)
    finally:c.close()
def until(expected):
    deadline=time.monotonic()+10
    while time.monotonic()<deadline:
        try:
            if api('/proxies/proxy').get('now')==expected:return
        except (OSError,RuntimeError):pass
        time.sleep(.1)
    raise AssertionError(f'Auto did not select {expected}')
subprocess.run([ENGINE,'check','-c','stdin'],input=json.dumps(config),text=True,check=True)
print('PASS: generated Auto configuration accepted by pinned native engine',flush=True)
if '--check-only' in sys.argv:
    for s in servers:s.shutdown();s.server_close()
    sys.exit(0)
process=subprocess.Popen([ENGINE,'run','-c','stdin'],stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,text=True)
try:
    process.stdin.write(json.dumps(config));process.stdin.close();until('yay-node-1')
    print('PASS: native engine selected faster working proxy')
    servers[1].working=False
    api('/group/proxy/delay?url=http%3A%2F%2F127.0.0.1%2Fgenerate_204&timeout=1500')
    until('yay-node-0')
    c=http.client.HTTPConnection('127.0.0.1',inbound,timeout=3)
    c.request('GET','http://127.0.0.1/generate_204');resp=c.getresponse();assert resp.status==204;c.close()
    print('PASS: native engine failed over and new traffic used remaining working proxy')
finally:
    process.terminate()
    try:process.wait(timeout=5)
    except subprocess.TimeoutExpired:process.kill();process.wait()
    for s in servers:s.shutdown();s.server_close()
    error=process.stderr.read()
    if error:print(error[-2000:])
