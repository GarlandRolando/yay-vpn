using System.IO;
using System.Net;
using System.Net.Http;

namespace YayVpn;
static class InternetProbe {
    static readonly string[] Urls={"https://www.gstatic.com/generate_204","https://cp.cloudflare.com/generate_204"};
    internal static async Task Verify(HttpClient client,CancellationToken ct){
        using var budget=CancellationTokenSource.CreateLinkedTokenSource(ct);budget.CancelAfter(TimeSpan.FromSeconds(5));
        async Task<bool> Probe(string url){
            try{using var response=await client.GetAsync(url,HttpCompletionOption.ResponseHeadersRead,budget.Token).ConfigureAwait(false);return response.StatusCode==HttpStatusCode.NoContent&&!budget.IsCancellationRequested;}
            catch(Exception e) when(e is HttpRequestException or OperationCanceledException or IOException){return false;}
        }
        var primary=Probe(Urls[0]);Task<bool>? fallback=null;
        try{
            await Task.WhenAny(primary,Task.Delay(500,budget.Token)).ConfigureAwait(false);ct.ThrowIfCancellationRequested();
            if(primary.IsCompleted&&await primary.ConfigureAwait(false))return;
            fallback=Probe(Urls[1]);var pending=new List<Task<bool>>{primary,fallback};
            while(pending.Count>0){var result=await Task.WhenAny(pending).ConfigureAwait(false);pending.Remove(result);ct.ThrowIfCancellationRequested();if(await result.ConfigureAwait(false))return;}
            throw new IOException("VPN internet check unavailable. Try another country or network.");
        }finally{
            budget.Cancel();await primary.ConfigureAwait(false);if(fallback!=null)await fallback.ConfigureAwait(false);
        }
    }
    internal static bool TemporaryHttp(int status)=>status is 408 or 425 or 429 || status is >=500 and <=599;
}
