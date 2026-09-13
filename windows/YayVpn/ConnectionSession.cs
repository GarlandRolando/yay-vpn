namespace YayVpn;

// Each attempt owns its work. Stop invalidates it before disposing any resources.
sealed class ConnectionSession : IDisposable {
    readonly object gate=new();
    readonly CancellationTokenSource lifetime=new();
    readonly List<IDisposable> resources=new();
    bool disposed,connected;
    internal CancellationToken Token {get;}
    internal ConnectionSession(){Token=lifetime.Token;}
    internal bool Connected {get{lock(gate)return connected&&!disposed;}}
    internal T Own<T>(T resource) where T:IDisposable {
        lock(gate){if(!disposed){resources.Add(resource);return resource;}}
        resource.Dispose();throw new OperationCanceledException(Token);
    }
    internal void MarkConnected(){lock(gate){Token.ThrowIfCancellationRequested();if(disposed)throw new OperationCanceledException(Token);connected=true;}}
    public void Dispose(){
        IDisposable[] closing;
        lock(gate){if(disposed)return;disposed=true;connected=false;closing=resources.ToArray();resources.Clear();}
        try{lifetime.Cancel();}finally{
            for(int i=closing.Length-1;i>=0;i--)try{closing[i].Dispose();}catch{}
            lifetime.Dispose();
        }
    }
}
