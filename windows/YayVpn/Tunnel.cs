using System.IO;
using System.Diagnostics;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text.Json.Nodes;

namespace YayVpn;
sealed class Tunnel : IDisposable {
    readonly YayApi api;
    readonly object stateGate=new();
    ConnectionSession? current;
    LiveTelemetry? telemetry;
    bool healthy;
    string lastError="";
    internal bool Connected {get{lock(stateGate)return current?.Connected==true;}}
    internal bool InternetHealthy {get{lock(stateGate)return current?.Connected==true&&healthy;}}
    internal string LastError {get{lock(stateGate)return lastError;}}
    internal LiveTelemetry? Telemetry {get{lock(stateGate)return telemetry;}}
    internal event Action? Stopped;
    internal Tunnel(YayApi api){this.api=api;}

    internal Task Connect(string id,CancellationToken ct)=>ConnectPool(new[]{id},false,ct);
    internal Task ConnectAuto(IReadOnlyList<string> ids,CancellationToken ct)=>ConnectPool(ids,true,ct);
    sealed record Member(string Id,int Revision,JsonObject Config,long Requested,double Lease);
    async Task<List<Member>> Authorize(IReadOnlyList<string> ids,bool auto,CancellationToken ct){
        using var gate=new SemaphoreSlim(4);
        var values=await Task.WhenAll(ids.Select(async id=>{
            await gate.WaitAsync(ct).ConfigureAwait(false);
            try{
                long requested=Stopwatch.GetTimestamp();
                var grant=await api.Call("POST","/v1/connect",new(){["server_id"]=id},ct).ConfigureAwait(false);
                return new Member(id,grant["revision"]!.GetValue<int>(),grant["config"]!.AsObject(),requested,grant["lease_seconds"]!.GetValue<double>());
            }catch(ApiException e) when(auto&&(e.Status==404||e.Status==409)){return null;}
            catch(Exception e) when(auto&&!ct.IsCancellationRequested&&(e is System.Net.Http.HttpRequestException||e is TaskCanceledException)){return null;}
            finally{gate.Release();}
        })).ConfigureAwait(false);
        var members=values.OfType<Member>().Where(m=>m.Lease-Stopwatch.GetElapsedTime(m.Requested).TotalSeconds>(auto?10:0)).ToList();
        if(members.Count==0)throw new IOException("Could not authorize Auto servers. Check cloud access or refresh locations.");
        return members;
    }
    async Task ConnectPool(IReadOnlyList<string> ids,bool auto,CancellationToken ct){
        var attempt=new ConnectionSession();ConnectionSession? previous;
        lock(stateGate){previous=current;current=attempt;telemetry=null;healthy=false;lastError="";}
        previous?.Dispose();
        using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(45));
        using var startup=CancellationTokenSource.CreateLinkedTokenSource(ct,attempt.Token,timeout.Token);
        using var cancelRegistration=ct.Register(()=>StopAttempt(attempt,""));
        using var timeoutRegistration=timeout.Token.Register(()=>StopAttempt(attempt,"Connection timed out. Try another country or network."));
        CancellationToken work=startup.Token;
        try{
            work.ThrowIfCancellationRequested();
            var members=await Authorize(ids,auto,work).ConfigureAwait(false);
            var first=members[0];string id=first.Id;int revision=first.Revision;
            var config=auto?AutoPool.Build(members.Select(m=>m.Config).ToList()):first.Config;
            work.ThrowIfCancellationRequested();
            config["inbounds"]![0]!["interface_name"]="YayVPN";config["inbounds"]![0]!["strict_route"]=true;
            // Cloud traffic uses the tunnel's normal route. Only upstream core sockets bypass it.
            int port;var reservation=new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback,0);reservation.Start();port=((System.Net.IPEndPoint)reservation.LocalEndpoint).Port;reservation.Stop();
            string password=Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(24));
            config["inbounds"]!.AsArray().Add(new JsonObject{["type"]="http",["tag"]="health",["listen"]="127.0.0.1",["listen_port"]=port,["users"]=new JsonArray(new JsonObject{["username"]="yay",["password"]=password})});
            var metrics=attempt.Own(new LiveTelemetry(config));
            work.ThrowIfCancellationRequested();
            var engine=attempt.Own(new EngineProcess());
            await engine.Configure(config,work).ConfigureAwait(false);
            var check=attempt.Own(new HttpClient(new HttpClientHandler{
                Proxy=new System.Net.WebProxy("http://127.0.0.1:"+port){Credentials=new System.Net.NetworkCredential("yay",password)},
                UseProxy=true,AllowAutoRedirect=false
            }){Timeout=Timeout.InfiniteTimeSpan});
            bool ready=false;
            for(int retry=0;retry<3;retry++){
                work.ThrowIfCancellationRequested();if(engine.HasExited)throw new IOException("VPN engine could not start");
                try{await InternetProbe.Verify(check,work).ConfigureAwait(false);ready=true;break;}catch(IOException) when(retry<2){}
                await Task.Delay(500,work).ConfigureAwait(false);
            }
            if(!ready)throw new IOException("VPN internet check failed");
            work.ThrowIfCancellationRequested();
            double remaining=members.Min(m=>m.Lease-Stopwatch.GetElapsedTime(m.Requested).TotalSeconds);
            if(remaining<=0)throw new IOException("Access expired");
            lock(stateGate){
                work.ThrowIfCancellationRequested();if(!ReferenceEquals(current,attempt))throw new OperationCanceledException(work);
                attempt.MarkConnected();telemetry=metrics;healthy=true;lastError="";
            }
            metrics.Start();
            _=Task.Run(()=>CheckHealth(attempt,check));
            if(auto)_=Task.Run(()=>WatchAuto(attempt,engine,members));
            else _=Task.Run(()=>Watch(attempt,engine,id,revision,remaining));
        }catch{
            bool cancelled=attempt.Token.IsCancellationRequested||ct.IsCancellationRequested;
            StopAttempt(attempt,LastError);
            if(timeout.IsCancellationRequested&&!ct.IsCancellationRequested)throw new TimeoutException("Connection timed out. Try another country or network.");
            if(cancelled)throw new OperationCanceledException(attempt.Token);
            throw;
        }
    }

    async Task CheckHealth(ConnectionSession attempt,HttpClient check){
        var ct=attempt.Token;
        try{while(!ct.IsCancellationRequested){
            await Task.Delay(15000,ct).ConfigureAwait(false);
            bool success=true;
            try{await InternetProbe.Verify(check,ct).ConfigureAwait(false);}catch(IOException){success=false;}
            lock(stateGate){
                if(!ReferenceEquals(current,attempt)||ct.IsCancellationRequested)return;
                healthy=success;
                lastError=success?"":"Connection interrupted. Retrying automatically…";
            }
            // Probe failure is advisory. Never disconnect here; the user owns tunnel lifetime.
        }}catch(OperationCanceledException){}catch(ObjectDisposedException){}
    }

    async Task Watch(ConnectionSession attempt,EngineProcess engine,string id,int revision,double remaining){
        var ct=attempt.Token;long renewed=Stopwatch.GetTimestamp();double lease=remaining;
        long next=Environment.TickCount64+45000,renewedRequest=0;Task<JsonObject>? renewal=null;
        string reason="";
        try{while(!ct.IsCancellationRequested){
            if(engine.HasExited){reason="VPN engine stopped unexpectedly.";break;}
            if(Stopwatch.GetElapsedTime(renewed).TotalSeconds>=lease){
                // Losing the cloud heartbeat during a brief network interruption is not a
                // user disconnect. Keep the tunnel alive and continue retrying heartbeats.
                lock(stateGate){if(ReferenceEquals(current,attempt)){healthy=false;lastError="Connection interrupted. Retrying automatically…";}}
                next=Math.Min(next,Environment.TickCount64+5000);
            }
            if(renewal==null&&Environment.TickCount64>=next){next=Environment.TickCount64+45000;renewedRequest=Stopwatch.GetTimestamp();renewal=api.Call("POST","/v1/heartbeat",new(){["server_id"]=id,["revision"]=revision},ct);}
            if(renewal?.IsCompleted==true){
                try{
                    var grant=await renewal.ConfigureAwait(false);renewed=renewedRequest;lease=grant["lease_seconds"]!.GetValue<double>();
                    lock(stateGate){if(ReferenceEquals(current,attempt)){lastError="";}}
                }
                catch(ApiException e) when(InternetProbe.TemporaryHttp(e.Status)){
                    lock(stateGate){if(ReferenceEquals(current,attempt)){healthy=false;lastError="Connection interrupted. Retrying automatically…";}}
                    next=Math.Min(next,Environment.TickCount64+5000);
                }
                catch(ApiException e){reason="Access check rejected (HTTP "+e.Status+"). Sign in again or contact support.";break;}
                catch(Exception) when(!ct.IsCancellationRequested){
                    lock(stateGate){if(ReferenceEquals(current,attempt)){healthy=false;lastError="Connection interrupted. Retrying automatically…";}}
                    next=Math.Min(next,Environment.TickCount64+5000);
                }
                renewal=null;
            }
            await Task.Delay(500,ct).ConfigureAwait(false);
        }}catch(OperationCanceledException){return;}catch(ObjectDisposedException){return;}
        finally{if(renewal!=null)_=Observe(renewal);}
        // Only terminal failures reach here. Temporary loss never disposes the session.
        if(!ct.IsCancellationRequested&&StopAttempt(attempt,reason))Stopped?.Invoke();
    }
    async Task WatchAuto(ConnectionSession attempt,EngineProcess engine,List<Member> members){
        var ct=attempt.Token;string reason="";long next=Environment.TickCount64+45000;
        var deadlines=members.Select(m=>Environment.TickCount64+(long)((m.Lease-Stopwatch.GetElapsedTime(m.Requested).TotalSeconds)*1000)).ToArray();
        Task? renewal=null;
        try{while(!ct.IsCancellationRequested){
            if(engine.HasExited){reason="VPN engine stopped unexpectedly.";break;}
            if(deadlines.Min()<=Environment.TickCount64){reason="Auto access check expired. Reconnect when the cloud service is reachable.";break;}
            if(renewal==null&&Environment.TickCount64>=next){
                next=Environment.TickCount64+45000;
                renewal=RenewAuto(members,deadlines,ct);
            }
            if(renewal?.IsCompleted==true){
                try{await renewal.ConfigureAwait(false);}
                catch(ApiException e) when(InternetProbe.TemporaryHttp(e.Status)){next=Environment.TickCount64+5000;}
                catch(ApiException e){reason="Auto access rejected (HTTP "+e.Status+"). Refresh locations or sign in again.";break;}
                catch(Exception) when(!ct.IsCancellationRequested){next=Environment.TickCount64+5000;}
                renewal=null;
            }
            await Task.Delay(500,ct).ConfigureAwait(false);
        }}catch(OperationCanceledException){return;}catch(ObjectDisposedException){return;}
        finally{if(renewal!=null)_=Observe(renewal);}
        if(!ct.IsCancellationRequested&&StopAttempt(attempt,reason))Stopped?.Invoke();
    }
    async Task RenewAuto(List<Member> members,long[] deadlines,CancellationToken ct){
        using var gate=new SemaphoreSlim(4);
        var failures=await Task.WhenAll(members.Select(async(m,i)=>{
            await gate.WaitAsync(ct).ConfigureAwait(false);
            try{long requested=Environment.TickCount64;
                var grant=await api.Call("POST","/v1/heartbeat",new(){["server_id"]=m.Id,["revision"]=m.Revision},ct).ConfigureAwait(false);
                Interlocked.Exchange(ref deadlines[i],requested+(long)(grant["lease_seconds"]!.GetValue<double>()*1000));
                return (Exception?)null;
            }catch(Exception e){return e;}finally{gate.Release();}
        })).ConfigureAwait(false);
        ct.ThrowIfCancellationRequested();
        var failure=failures.FirstOrDefault(e=>e is ApiException a&&!InternetProbe.TemporaryHttp(a.Status))??failures.FirstOrDefault(e=>e!=null);
        if(failure!=null)throw failure;
    }
    static async Task Observe(Task task){try{await task.ConfigureAwait(false);}catch{}}
    bool StopAttempt(ConnectionSession attempt,string reason){
        lock(stateGate){if(!ReferenceEquals(current,attempt))return false;current=null;telemetry=null;healthy=false;lastError=reason;}
        attempt.Dispose();return true;
    }
    internal void Stop(){
        ConnectionSession? old;lock(stateGate){old=current;current=null;telemetry=null;healthy=false;lastError="";}
        old?.Dispose();
    }
    public void Dispose()=>Stop();

    sealed class EngineProcess : IDisposable {
        readonly Process process;
        IntPtr job;
        int disposed;
        internal bool HasExited=>Volatile.Read(ref disposed)!=0||process.HasExited;
        internal EngineProcess(){
            var exe=Path.Combine(AppContext.BaseDirectory,"engine","sing-box.exe");
            if(!File.Exists(exe))throw new IOException("Native VPN engine missing. Extract the complete app ZIP.");
            var start=new ProcessStartInfo(exe,"run -c stdin"){UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true,RedirectStandardError=true,RedirectStandardOutput=true,WorkingDirectory=Path.GetDirectoryName(exe)!};
            process=Process.Start(start)??throw new IOException("Could not start VPN engine");
            try{
                job=CreateJobObject(IntPtr.Zero,null);if(job==IntPtr.Zero)throw new IOException("Could not create process guard");
                var info=new ExtendedLimits{BasicLimitInformation=new BasicLimits{LimitFlags=0x2000}};
                if(!SetInformationJobObject(job,9,ref info,(uint)Marshal.SizeOf<ExtendedLimits>())||!AssignProcessToJobObject(job,process.Handle))throw new IOException("Could not guard VPN engine lifecycle");
                _=Observe(process.StandardError.ReadToEndAsync());_=Observe(process.StandardOutput.ReadToEndAsync());
            }catch{Dispose();throw;}
        }
        internal async Task Configure(JsonObject config,CancellationToken ct){ct.ThrowIfCancellationRequested();await process.StandardInput.WriteAsync(config.ToJsonString().AsMemory(),ct).ConfigureAwait(false);process.StandardInput.Close();}
        public void Dispose(){
            if(Interlocked.Exchange(ref disposed,1)!=0)return;
            if(job!=IntPtr.Zero){CloseHandle(job);job=IntPtr.Zero;}
            try{if(!process.HasExited)process.Kill(true);}catch{}
            process.Dispose();
        }
    }
    [StructLayout(LayoutKind.Sequential)]struct BasicLimits{public long PerProcessUserTimeLimit,PerJobUserTimeLimit;public uint LimitFlags;public UIntPtr MinimumWorkingSetSize,MaximumWorkingSetSize;public uint ActiveProcessLimit;public UIntPtr Affinity;public uint PriorityClass,SchedulingClass;}
    [StructLayout(LayoutKind.Sequential)]struct IoCounters{public ulong ReadOperationCount,WriteOperationCount,OtherOperationCount,ReadTransferCount,WriteTransferCount,OtherTransferCount;}
    [StructLayout(LayoutKind.Sequential)]struct ExtendedLimits{public BasicLimits BasicLimitInformation;public IoCounters IoInfo;public UIntPtr ProcessMemoryLimit,JobMemoryLimit,PeakProcessMemoryUsed,PeakJobMemoryUsed;}
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern IntPtr CreateJobObject(IntPtr attributes,string? name);
    [DllImport("kernel32.dll",SetLastError=true)]static extern bool SetInformationJobObject(IntPtr job,int type,ref ExtendedLimits info,uint length);
    [DllImport("kernel32.dll",SetLastError=true)]static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
    [DllImport("kernel32.dll")]static extern bool CloseHandle(IntPtr handle);
}

