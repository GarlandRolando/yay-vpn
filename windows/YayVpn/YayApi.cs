using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace YayVpn;
sealed class YayApi : IDisposable {
    internal const string Base="https://vjcpphrhdzdywmrfndta.supabase.co/functions/v1/yay-api";
    // Retire pooled sockets before the next heartbeat so they follow the current VPN route.
    readonly HttpClient http=new(new SocketsHttpHandler { AllowAutoRedirect=false,UseProxy=false,PooledConnectionLifetime=TimeSpan.FromSeconds(15),ConnectTimeout=TimeSpan.FromSeconds(7) }) { Timeout=TimeSpan.FromSeconds(14) };
    readonly ECDsa key=ECDsa.Create();
    readonly string directory=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"YayVPN");
    internal string Token { get; private set; }="";
    internal YayApi(){
        Directory.CreateDirectory(directory);string path=Path.Combine(directory,"device.bin");
        if(File.Exists(path)){var raw=Unprotect(File.ReadAllBytes(path));try{key.ImportPkcs8PrivateKey(raw,out _);}finally{CryptographicOperations.ZeroMemory(raw);}}
        else {key.GenerateKey(ECCurve.NamedCurves.nistP256);var raw=key.ExportPkcs8PrivateKey();try{File.WriteAllBytes(path,Protect(raw));}finally{CryptographicOperations.ZeroMemory(raw);}}
        var session=Path.Combine(directory,"session.bin");if(File.Exists(session))Token=Encoding.UTF8.GetString(Unprotect(File.ReadAllBytes(session)));
    }
    static byte[] Protect(byte[] data)=>ProtectedData.Protect(data,null,DataProtectionScope.CurrentUser);
    static byte[] Unprotect(byte[] data)=>ProtectedData.Unprotect(data,null,DataProtectionScope.CurrentUser);
    internal async Task<JsonObject> Call(string method,string path,JsonObject? data=null,CancellationToken ct=default){
        byte[] body=data==null?Array.Empty<byte>():Encoding.UTF8.GetBytes(data.ToJsonString());
        string time=DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString(System.Globalization.CultureInfo.InvariantCulture),nonce=Guid.NewGuid().ToString("N");
        string message=string.Join("\n",method,path,time,nonce,Convert.ToHexString(SHA256.HashData(body)).ToLowerInvariant());
        byte[] signature;lock(key){signature=key.SignData(Encoding.UTF8.GetBytes(message),HashAlgorithmName.SHA256,DSASignatureFormat.Rfc3279DerSequence);}
        using var request=new HttpRequestMessage(new HttpMethod(method),Base+path);
        request.Headers.Add("X-Yay-Time",time);request.Headers.Add("X-Yay-Nonce",nonce);request.Headers.Add("X-Yay-Signature",Convert.ToBase64String(signature));
        if(Token.Length>0)request.Headers.Authorization=new("Bearer",Token);
        if(data!=null){request.Content=new ByteArrayContent(body);request.Content.Headers.ContentType=new("application/json");}
        using var response=await http.SendAsync(request,ct);string raw=await response.Content.ReadAsStringAsync(ct);
        JsonObject result;
        try{result=JsonNode.Parse(raw) as JsonObject??throw new System.Text.Json.JsonException();}
        catch(System.Text.Json.JsonException){if(!response.IsSuccessStatusCode)throw new ApiException((int)response.StatusCode,"Cloud request failed");throw new IOException("Unexpected cloud response");}
        if(!response.IsSuccessStatusCode)throw new ApiException((int)response.StatusCode,result["error"]?.ToString()??"Cloud request failed");return result;
    }
    internal void ClearSession(){Token="";var file=Path.Combine(directory,"session.bin");if(File.Exists(file))File.Delete(file);}
    internal async Task Login(string user,string password){
        var result=await Call("POST","/v1/login",new(){["username"]=user,["password"]=password,["public_key"]=Convert.ToBase64String(key.ExportSubjectPublicKeyInfo()),["device_name"]="Windows · "+Environment.MachineName});
        Token=result["token"]!.GetValue<string>();File.WriteAllBytes(Path.Combine(directory,"session.bin"),Protect(Encoding.UTF8.GetBytes(Token)));
    }
    internal async Task Logout(){try{await Call("POST","/v1/logout",new());}finally{Token="";File.Delete(Path.Combine(directory,"session.bin"));}}
    public void Dispose(){key.Dispose();http.Dispose();}
}
sealed class ApiException(int status,string message):IOException(message){internal int Status=>status;}
