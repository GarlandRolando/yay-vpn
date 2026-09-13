using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json.Nodes;

namespace YayVpn;

/** An authenticated, per-connection loopback controller. Never writes secrets to disk. */
sealed class LiveTelemetry : IDisposable {
    internal sealed record TrafficSample(long Up,long Down,long At);
    internal sealed record DelaySample(long Millis,long At);
    internal volatile TrafficSample? Traffic;
    internal volatile DelaySample? Latency;
    readonly CancellationTokenSource lifetime=new();
    readonly HttpClient client;
    readonly string delayPath;
    int disposed;

    internal LiveTelemetry(JsonObject config){
        var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();int port=((IPEndPoint)listener.LocalEndpoint).Port;listener.Stop();
        string secret=Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        var experimental=config["experimental"] as JsonObject;
        if(experimental==null){experimental=new();config["experimental"]=experimental;}
        experimental["clash_api"]=new JsonObject{["external_controller"]="127.0.0.1:"+port,["secret"]=secret};
        string tag=config["outbounds"]![0]!["tag"]!.GetValue<string>();
        delayPath="proxies/"+Uri.EscapeDataString(tag)+"/delay?timeout=4000&url="+Uri.EscapeDataString("https://www.gstatic.com/generate_204");
        client=new HttpClient(new HttpClientHandler{UseProxy=false,AllowAutoRedirect=false}){BaseAddress=new Uri("http://127.0.0.1:"+port+"/"),Timeout=Timeout.InfiniteTimeSpan};
        client.DefaultRequestHeaders.Authorization=new AuthenticationHeaderValue("Bearer",secret);
    }
    internal void Start(){var ct=lifetime.Token;_=Task.Run(()=>ReadTraffic(ct));_=Task.Run(()=>ReadLatency(ct));}
    async Task ReadTraffic(CancellationToken ct){
        try{while(!ct.IsCancellationRequested){
            try{
                using var headerTimeout=CancellationTokenSource.CreateLinkedTokenSource(ct);headerTimeout.CancelAfter(6000);
                using var response=await client.GetAsync("traffic",HttpCompletionOption.ResponseHeadersRead,headerTimeout.Token).ConfigureAwait(false);response.EnsureSuccessStatusCode();
                using var stream=await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);using var reader=new StreamReader(stream);
                while(!ct.IsCancellationRequested){
                    string? line=await reader.ReadLineAsync(ct).AsTask().WaitAsync(TimeSpan.FromSeconds(6),ct).ConfigureAwait(false);
                    if(line==null)break;if(line.Length>4096)throw new IOException("Invalid traffic sample");
                    var value=JsonNode.Parse(line)!;long up=value["up"]!.GetValue<long>(),down=value["down"]!.GetValue<long>();
                    if(up<0||down<0)throw new IOException("Invalid traffic counters");
                    if(!ct.IsCancellationRequested)Traffic=new(up,down,Environment.TickCount64);
                }
            }catch(Exception) when(!ct.IsCancellationRequested){Traffic=null;}
            await Task.Delay(2000,ct).ConfigureAwait(false);
        }}catch(OperationCanceledException){}catch(ObjectDisposedException){}catch(Exception) when(ct.IsCancellationRequested){}
        finally{Traffic=null;}
    }
    async Task ReadLatency(CancellationToken ct){
        try{while(!ct.IsCancellationRequested){
            try{
                using var timeout=CancellationTokenSource.CreateLinkedTokenSource(ct);timeout.CancelAfter(6000);
                using var response=await client.GetAsync(delayPath,timeout.Token).ConfigureAwait(false);response.EnsureSuccessStatusCode();
                var value=JsonNode.Parse(await response.Content.ReadAsStringAsync(timeout.Token).ConfigureAwait(false))!;long ms=value["delay"]!.GetValue<long>();
                if(ms<=0)throw new IOException("Invalid latency");if(!ct.IsCancellationRequested)Latency=new(ms,Environment.TickCount64);
            }catch(Exception) when(!ct.IsCancellationRequested){Latency=null;}
            await Task.Delay(15000,ct).ConfigureAwait(false);
        }}catch(OperationCanceledException){}catch(ObjectDisposedException){}catch(Exception) when(ct.IsCancellationRequested){}
        finally{Latency=null;}
    }
    public void Dispose(){if(Interlocked.Exchange(ref disposed,1)!=0)return;lifetime.Cancel();client.Dispose();lifetime.Dispose();Traffic=null;Latency=null;}
}
