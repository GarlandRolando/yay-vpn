using System.Diagnostics;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text.Json.Nodes;

namespace YayVpn;
sealed class Tunnel : IDisposable {
    readonly YayApi api;
    readonly object stateGate=new(); long generation;
    Process? engine;IntPtr job;
    CancellationTokenSource? monitor;
    internal bool Connected {get;private set;}
    internal event Action? Stopped;
    internal Tunnel(YayApi api){this.api=api;}
    internal async Task Connect(string id,CancellationToken ct){
        Stop();long activeGeneration;lock(stateGate){activeGeneration=generation;}long requested=Stopwatch.GetTimestamp();var grant=await api.Call("POST","/v1/connect",new(){["server_id"]=id},ct);
        int revision=grant["revision"]!.GetValue<int>();double deadline=Stopwatch.GetElapsedTime(requested).TotalSeconds;
        long expiry=grant["expires_at"]!.GetValue<long>();double lease=grant["lease_seconds"]!.GetValue<double>()-deadline;
        if(lease<=0)throw new IOException("Access expired");
        var config=grant["config"]!.AsObject();
        config["inbounds"]![0]!["interface_name"]="YayVPN";config["inbounds"]![0]!["strict_route"]=true;
        config["outbounds"]!.AsArray().Add(new JsonObject{["type"]="direct",["tag"]="cloud-direct"});
        config["route"]!["rules"]!.AsArray().Insert(1,new JsonObject{["domain"]=new JsonArray("vjcpphrhdzdywmrfndta.supabase.co"),["outbound"]="cloud-direct"});
        // An authenticated loopback HTTP proxy verifies this specific core, avoiding a false direct-network success.
        int port;var listener=new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback,0);listener.Start();port=((System.Net.IPEndPoint)listener.LocalEndpoint).Port;listener.Stop();
        string probePassword=Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(24));
        config["inbounds"]!.AsArray().Add(new JsonObject{["type"]="http",["tag"]="health",["listen"]="127.0.0.1",["listen_port"]=port,["users"]=new JsonArray(new JsonObject{["username"]="yay",["password"]=probePassword})});
        var exe=Path.Combine(AppContext.BaseDirectory,"engine","sing-box.exe");if(!File.Exists(exe))throw new IOException("Native VPN engine missing. Extract the complete app ZIP.");
        var start=new ProcessStartInfo(exe,"run -c stdin"){UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true,RedirectStandardError=true,RedirectStandardOutput=true,WorkingDirectory=Path.GetDirectoryName(exe)!};
        try{
            engine=Process.Start(start)??throw new IOException("Could not start VPN engine");
            job=CreateJobObject(IntPtr.Zero,null);if(job==IntPtr.Zero)throw new IOException("Could not create process guard");
            var info=new ExtendedLimits{BasicLimitInformation=new BasicLimits{LimitFlags=0x2000}};
            if(!SetInformationJobObject(job,9,ref info,(uint)Marshal.SizeOf<ExtendedLimits>())||!AssignProcessToJobObject(job,engine.Handle))throw new IOException("Could not guard VPN engine lifecycle");
            _=engine.StandardError.ReadToEndAsync();_=engine.StandardOutput.ReadToEndAsync();
            await engine.StandardInput.WriteAsync(config.ToJsonString());engine.StandardInput.Close();
            using var checkHandler=new HttpClientHandler{Proxy=new System.Net.WebProxy("http://127.0.0.1:"+port){Credentials=new System.Net.NetworkCredential("yay",probePassword)},UseProxy=true,AllowAutoRedirect=false};
            using var check=new HttpClient(checkHandler){Timeout=TimeSpan.FromSeconds(8)};
            bool ready=false;for(int attempt=0;attempt<3;attempt++){
                ct.ThrowIfCancellationRequested();if(engine.HasExited)throw new IOException("VPN engine could not start");
                try{using var response=await check.GetAsync("https://www.gstatic.com/generate_204",ct);if(response.StatusCode==System.Net.HttpStatusCode.NoContent){ready=true;break;}}catch(HttpRequestException){}catch(TaskCanceledException) when(!ct.IsCancellationRequested){}
                await Task.Delay(500,ct);
            }
            if(!ready)throw new IOException("VPN internet check failed");
            double remaining=grant["lease_seconds"]!.GetValue<double>()-Stopwatch.GetElapsedTime(requested).TotalSeconds;
            if(remaining<=0)throw new IOException("Access expired");Connected=true;monitor=new();var watcherToken=monitor.Token;_=Task.Run(()=>Watch(id,revision,remaining,expiry,activeGeneration,watcherToken));
        }catch{Stop();throw;}
    }
    async Task Watch(string id,int revision,double remaining,long expiry,long activeGeneration,CancellationToken ct){
        long renewed=Stopwatch.GetTimestamp();double lease=remaining;long next=Environment.TickCount64+45000;long renewedRequest=0;Task<JsonObject>? renewal=null;
        try{while(!ct.IsCancellationRequested){
            bool valid;lock(stateGate){valid=activeGeneration==generation&&engine!=null&&!engine.HasExited;}
            if(!valid||Stopwatch.GetElapsedTime(renewed).TotalSeconds>=lease||DateTimeOffset.UtcNow.ToUnixTimeSeconds()>=expiry)break;
            if(renewal==null&&Environment.TickCount64>=next){next=Environment.TickCount64+45000;renewedRequest=Stopwatch.GetTimestamp();renewal=api.Call("POST","/v1/heartbeat",new(){["server_id"]=id,["revision"]=revision},ct);}
            if(renewal?.IsCompleted==true){try{var g=await renewal;renewed=renewedRequest;lease=g["lease_seconds"]!.GetValue<double>();expiry=g["expires_at"]!.GetValue<long>();}catch(ApiException){break;}catch(Exception){}renewal=null;}
            await Task.Delay(500,ct).ConfigureAwait(false);
        }}catch(OperationCanceledException){return;}catch(ObjectDisposedException){return;}
        bool stopped=false;lock(stateGate){if(!ct.IsCancellationRequested&&activeGeneration==generation){Stop();stopped=true;}}
        if(stopped)Stopped?.Invoke();
    }
    internal void Stop(){lock(stateGate){generation++;monitor?.Cancel();monitor?.Dispose();monitor=null;Connected=false;if(engine!=null){try{if(!engine.HasExited)engine.Kill(true);}catch{}engine.Dispose();engine=null;}if(job!=IntPtr.Zero){CloseHandle(job);job=IntPtr.Zero;}}}
    public void Dispose()=>Stop();
    [StructLayout(LayoutKind.Sequential)]struct BasicLimits{public long PerProcessUserTimeLimit,PerJobUserTimeLimit;public uint LimitFlags;public UIntPtr MinimumWorkingSetSize,MaximumWorkingSetSize;public uint ActiveProcessLimit;public UIntPtr Affinity;public uint PriorityClass,SchedulingClass;}
    [StructLayout(LayoutKind.Sequential)]struct IoCounters{public ulong ReadOperationCount,WriteOperationCount,OtherOperationCount,ReadTransferCount,WriteTransferCount,OtherTransferCount;}
    [StructLayout(LayoutKind.Sequential)]struct ExtendedLimits{public BasicLimits BasicLimitInformation;public IoCounters IoInfo;public UIntPtr ProcessMemoryLimit,JobMemoryLimit,PeakProcessMemoryUsed,PeakJobMemoryUsed;}
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern IntPtr CreateJobObject(IntPtr attributes,string? name);
    [DllImport("kernel32.dll",SetLastError=true)]static extern bool SetInformationJobObject(IntPtr job,int type,ref ExtendedLimits info,uint length);
    [DllImport("kernel32.dll",SetLastError=true)]static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
    [DllImport("kernel32.dll")]static extern bool CloseHandle(IntPtr handle);
}
