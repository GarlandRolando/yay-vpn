namespace YayVpn;

// Endpoints come from the reviewed build, never from an unauthenticated redirect.
internal sealed class ApiRoutes {
    readonly string direct,relay;
    volatile string preferred;
    internal ApiRoutes(string direct,string? relay){this.direct=Valid(direct);this.relay=string.IsNullOrWhiteSpace(relay)?"":Valid(relay);preferred=this.relay.Length>0?this.relay:this.direct;}
    internal static string Valid(string value){
        string text=value.Trim();
        if(!System.Text.RegularExpressions.Regex.IsMatch(text,@"\Ahttps://[a-zA-Z0-9][a-zA-Z0-9.-]*\.[a-zA-Z]{2,}(?::443)?(?:/[a-zA-Z0-9_-]+)*/?\z")||!Uri.TryCreate(text,UriKind.Absolute,out var u)||u.Scheme!="https"||!System.Text.RegularExpressions.Regex.IsMatch(u.Host,@"\A[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,}\z",System.Text.RegularExpressions.RegexOptions.IgnoreCase)||u.UserInfo.Length>0||u.Query.Length>0||u.Fragment.Length>0||!u.IsDefaultPort||!System.Text.RegularExpressions.Regex.IsMatch(u.AbsolutePath,@"\A(?:/[A-Za-z0-9_-]+)*/?\z")||text.Contains('?')||text.Contains('#')||text.Contains('\\'))throw new ArgumentException("Login endpoint must be a plain HTTPS domain and path");
        return text.TrimEnd('/');
    }
    internal async Task<T> Call<T>(Func<string,Task<T>> attempt,Func<Exception,bool> retryable){
        string first=preferred,second=first==direct?relay:direct;
        try{T result=await attempt(first);preferred=first;return result;}
        catch(Exception error)when(second.Length>0&&second!=first&&retryable(error)){}
        T fallback=await attempt(second);preferred=second;return fallback;
    }
}
