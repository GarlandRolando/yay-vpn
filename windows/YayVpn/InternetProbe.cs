using System.IO;
using System.Net;
using System.Net.Http;

namespace YayVpn;
static class InternetProbe {
    static readonly string[] Urls={"https://www.gstatic.com/generate_204","https://cp.cloudflare.com/generate_204"};
    internal static async Task Verify(HttpClient client,CancellationToken ct){
        Exception? failure=null;
        foreach(string url in Urls){
            ct.ThrowIfCancellationRequested();
            try{
                using var budget=CancellationTokenSource.CreateLinkedTokenSource(ct);budget.CancelAfter(TimeSpan.FromSeconds(8));
                using var response=await client.GetAsync(url,HttpCompletionOption.ResponseHeadersRead,budget.Token).ConfigureAwait(false);
                ct.ThrowIfCancellationRequested();
                if(response.StatusCode==HttpStatusCode.NoContent)return;
                failure=new IOException("Internet check returned an unexpected response");
            }catch(Exception e) when(e is HttpRequestException or OperationCanceledException or IOException){ct.ThrowIfCancellationRequested();failure=e;}
        }
        throw new IOException("VPN internet check unavailable. Try another country or network.",failure);
    }
    internal static bool TemporaryHttp(int status)=>status is 408 or 425 or 429 || status is >=500 and <=599;
}
