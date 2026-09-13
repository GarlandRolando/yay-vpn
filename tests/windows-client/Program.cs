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
monitor.Start();await Until(()=>monitor.Traffic?.Down==8192&&monitor.Latency?.Millis==73);
Check(monitor.Traffic!.Up==2048&&unauthorized==0&&delayRequests==1,"Wrong live readings or authorization");
monitor.Dispose();await Task.Delay(200);Check(monitor.Traffic==null&&monitor.Latency==null,"Stale readings after disconnect");
stop.Cancel();listener.Stop();await server;await Task.WhenAll(handlers);
Console.WriteLine("PASS: loopback auth, fresh secrets, streaming upload/download, proxy latency, cancellation and reset");
