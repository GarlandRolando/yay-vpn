using System.Net;
using System.Text;
using System.Text.Json.Nodes;
using YayVpn;

static void Check(bool condition,string message){if(!condition)throw new Exception(message);}
static async Task Until(Func<bool> condition){for(int i=0;i<100;i++){if(condition())return;await Task.Delay(30);}throw new Exception("Metric update timed out");}
var country=new[]{"sg-1","sg-2","sg-3"};var seen=new HashSet<string>();
for(int seed=0;seed<100;seed++){var picked=ConnectionPolicy.Lightning(country,new Random(seed));Check(picked.Count==1&&country.Contains(picked[0]),"Lightning crossed country boundary");seen.Add(picked[0]);}
Check(seen.Count==3,"Lightning not distributing selections");
Check(ConnectionPolicy.Lightning(Array.Empty<string>(),new Random(1)).Count==0,"Empty country selected");
Check(ConnectionPolicy.Lightning(new[]{"only"},new Random(1)).SequenceEqual(new[]{"only"}),"Single server selection failed");
var pings=new Dictionary<string,long>{{"dead",-1},{"best",100},{"close",108},{"outside",111},{"slow",500}};
for(int seed=0;seed<100;seed++){var order=ConnectionPolicy.Enhanced(pings,new Random(seed));Check(order.Count==3&&new[]{"best","close"}.Contains(order[0])&&order[2]=="outside"&&!order.Contains("dead"),"Enhanced ranking regression");}
Check(ConnectionPolicy.Enhanced(new Dictionary<string,long>{{"dead",-1}},new Random()).Count==0,"Enhanced used unreachable server");
Console.WriteLine("PASS: country isolation, random single selection, empty/single countries, Enhanced ranking and timeout exclusion");

JsonObject Config()=>new(){["outbounds"]=new JsonArray(new JsonObject{["tag"]="proxy"}),["experimental"]=new JsonObject{["cache_file"]=new JsonObject{["enabled"]=false}}};
var config=Config();using var monitor=new LiveTelemetry(config);
var controller=config["experimental"]!["clash_api"]!;string address=controller["external_controller"]!.GetValue<string>(),secret=controller["secret"]!.GetValue<string>();
Check(address.StartsWith("127.0.0.1:")&&secret.Length>=48,"Controller must be authenticated and loopback only");
Check(config["experimental"]!["cache_file"]!=null,"Existing experimental settings lost");
var config2=Config();using(var monitor2=new LiveTelemetry(config2))Check(secret!=config2["experimental"]!["clash_api"]!["secret"]!.GetValue<string>(),"Controller secret reused");
using var stop=new CancellationTokenSource();using var listener=new HttpListener();listener.Prefixes.Add("http://"+address+"/");listener.Start();
int unauthorized=0,delayRequests=0;
async Task Respond(HttpListenerContext ctx){
 try{
  if(ctx.Request.Headers["Authorization"]!="Bearer "+secret){Interlocked.Increment(ref unauthorized);ctx.Response.StatusCode=401;ctx.Response.Close();return;}
  ctx.Response.ContentType="application/json";
  if(ctx.Request.Url!.AbsolutePath=="/traffic"){
   ctx.Response.SendChunked=true;byte[] bytes=Encoding.UTF8.GetBytes("{\"up\":2048,\"down\":8192}\n");
   while(!stop.IsCancellationRequested){await ctx.Response.OutputStream.WriteAsync(bytes,stop.Token);await ctx.Response.OutputStream.FlushAsync(stop.Token);await Task.Delay(100,stop.Token);}
  }else{
   Check(ctx.Request.Url.AbsolutePath=="/proxies/proxy/delay"&&ctx.Request.QueryString["url"]=="https://www.gstatic.com/generate_204"&&ctx.Request.QueryString["timeout"]=="4000","Latency did not test selected proxy");
   Interlocked.Increment(ref delayRequests);await ctx.Response.OutputStream.WriteAsync(Encoding.UTF8.GetBytes("{\"delay\":73}\n"),stop.Token);
  }
 }catch(OperationCanceledException){}catch(HttpListenerException){}catch(IOException){}finally{ctx.Response.Close();}
}
var handlers=new List<Task>();var server=Task.Run(async()=>{try{while(!stop.IsCancellationRequested){var ctx=await listener.GetContextAsync().WaitAsync(stop.Token);handlers.Add(Respond(ctx));}}catch(OperationCanceledException){}catch(HttpListenerException){}catch(ObjectDisposedException){}});
try{
 monitor.Start();await Until(()=>monitor.Traffic?.Down==8192&&monitor.Latency?.Millis==73);
 Check(monitor.Traffic!.Up==2048&&unauthorized==0&&delayRequests==1,"Wrong live readings or authorization");
 monitor.Dispose();await Task.Delay(200);Check(monitor.Traffic==null&&monitor.Latency==null,"Stale readings after disconnect");
}finally{
 monitor.Dispose();stop.Cancel();
 // Windows responses use the listener's native request queue when closing.
 // Drain the accept loop and every response before disposing that queue.
 try{await server;await Task.WhenAll(handlers);}finally{listener.Stop();}
}
Console.WriteLine("PASS: loopback auth, fresh secrets, streaming upload/download, proxy latency, cancellation and reset");

using(var stopped=new ConnectionSession()){
    int disposed=0;stopped.Own(new OnDispose(()=>disposed++));stopped.MarkConnected();stopped.Dispose();stopped.Dispose();
    Check(!stopped.Connected&&stopped.Token.IsCancellationRequested&&disposed==1,"Stop did not invalidate the connection or clean up exactly once");
    try{stopped.MarkConnected();throw new Exception("Late startup resurrected connection");}catch(OperationCanceledException){}
    try{stopped.Own(new OnDispose(()=>disposed++));throw new Exception("Late engine was accepted");}catch(OperationCanceledException){}
    Check(disposed==2,"Late engine was leaked");
}
for(int i=0;i<100;i++){
    using var session=new ConnectionSession();int released=0;using var start=new ManualResetEventSlim();
    var create=Task.Run(()=>{start.Wait();try{session.Own(new OnDispose(()=>Interlocked.Increment(ref released)));session.MarkConnected();}catch(OperationCanceledException){}});
    var cancel=Task.Run(()=>{start.Wait();session.Dispose();});start.Set();await Task.WhenAll(create,cancel);
    Check(!session.Connected&&released==1,"Concurrent Stop leaked a resource or resurrected the connection");
}
using(var old=new ConnectionSession())using(var replacement=new ConnectionSession()){
    old.Dispose();replacement.MarkConnected();old.Dispose();Check(replacement.Connected,"Old cleanup stopped replacement");
}
Console.WriteLine("PASS: Stop invalidates startup, closes late resources, isolates replacement sessions and survives 100 startup/Stop races");

using(var handler=new ProbeHandler((request,index,ct)=>Task.FromResult(new System.Net.Http.HttpResponseMessage(index==1?HttpStatusCode.BadGateway:HttpStatusCode.NoContent))))
using(var http=new System.Net.Http.HttpClient(handler)){
    await InternetProbe.Verify(http,CancellationToken.None);
    Check(handler.Hosts.Count==2&&handler.Hosts.Distinct().Count()==2,"Probe fallback did not use a second provider");
}
using(var handler=new ProbeHandler((request,index,ct)=>index==1?Task.FromException<System.Net.Http.HttpResponseMessage>(new TaskCanceledException("Mock per-probe timeout")):Task.FromResult(new System.Net.Http.HttpResponseMessage(HttpStatusCode.NoContent))))
using(var http=new System.Net.Http.HttpClient(handler)){await InternetProbe.Verify(http,CancellationToken.None);Check(handler.Hosts.Count==2,"Timeout prevented fallback");}
using(var handler=new ProbeHandler((request,index,ct)=>Task.FromResult(new System.Net.Http.HttpResponseMessage(HttpStatusCode.Redirect))))
using(var http=new System.Net.Http.HttpClient(handler)){
    try{await InternetProbe.Verify(http,CancellationToken.None);throw new Exception("Redirect accepted as successful VPN test");}catch(IOException){}
    Check(handler.Hosts.Count==2,"Both probe endpoints were not checked");
}
using(var session=new ConnectionSession()){
    var entered=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
    using var handler=new ProbeHandler(async(request,index,ct)=>{entered.TrySetResult();await Task.Delay(Timeout.Infinite,ct);return new System.Net.Http.HttpResponseMessage(HttpStatusCode.NoContent);});
    using var http=new System.Net.Http.HttpClient(handler);
    var pending=InternetProbe.Verify(http,session.Token);await entered.Task.WaitAsync(TimeSpan.FromSeconds(2));session.Dispose();
    try{await pending.WaitAsync(TimeSpan.FromSeconds(2));throw new Exception("Stop did not cancel blocked HTTP request");}catch(OperationCanceledException){}
    Check(handler.Hosts.Count==1,"Cancelled probe launched fallback");
}
using(var session=new ConnectionSession()){
    session.MarkConnected();using var handler=new ProbeHandler((request,index,ct)=>Task.FromResult(new System.Net.Http.HttpResponseMessage(HttpStatusCode.ServiceUnavailable)));
    using var http=new System.Net.Http.HttpClient(handler);
    try{await InternetProbe.Verify(http,session.Token);}catch(IOException){}
    Check(session.Connected,"Advisory probe failure disconnected the session");
}
Check(new[]{408,425,429,500,502,503,504}.All(InternetProbe.TemporaryHttp),"Temporary heartbeat response was treated as revoked access");
Check(new[]{400,401,403,404,409,422}.All(status=>!InternetProbe.TemporaryHttp(status)),"Access denial or configuration error was ignored");
Console.WriteLine("PASS: independent probe fallback, probe timeouts, redirect rejection, blocked HTTP cancellation and transient/denied heartbeat classification");

sealed class OnDispose(Action close):IDisposable{public void Dispose()=>close();}
sealed class ProbeHandler(Func<System.Net.Http.HttpRequestMessage,int,CancellationToken,Task<System.Net.Http.HttpResponseMessage>> respond):System.Net.Http.HttpMessageHandler {
    internal List<string> Hosts {get;}=new();
    protected override Task<System.Net.Http.HttpResponseMessage> SendAsync(System.Net.Http.HttpRequestMessage request,CancellationToken ct){Hosts.Add(request.RequestUri!.Host);return respond(request,Hosts.Count,ct);}
}
