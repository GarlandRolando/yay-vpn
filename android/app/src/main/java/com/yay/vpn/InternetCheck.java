package com.yay.vpn;

import java.io.IOException;

/** A reachability sample, not proof that all internet traffic is healthy. */
final class InternetCheck {
    interface ScopedProbe {int status(String url,RequestScope scope)throws Exception;}
    static void verifyHedged(ScopedProbe probe,RequestScope parent)throws Exception {
        parent.check();RequestScope probes=new RequestScope();parent.track(probes);
        java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CompletionService<Boolean> results=new java.util.concurrent.ExecutorCompletionService<>(executor);
        java.util.List<java.util.concurrent.Future<Boolean>> tasks=new java.util.ArrayList<>();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        try{
            tasks.add(results.submit(()->probe.status(URLS[0],probes)==204));
            java.util.concurrent.Future<Boolean> first=results.poll(500,java.util.concurrent.TimeUnit.MILLISECONDS);parent.check();
            int remaining=first==null?2:1;
            if(first!=null){try{if(first.get()){parent.check();return;}}catch(java.util.concurrent.ExecutionException ignored){}}
            tasks.add(results.submit(()->probe.status(URLS[1],probes)==204));
            while(remaining>0&&System.nanoTime()<deadline){
                parent.check();java.util.concurrent.Future<Boolean> result=results.poll(100,java.util.concurrent.TimeUnit.MILLISECONDS);
                if(result==null)continue;remaining--;try{if(result.get()){parent.check();return;}}catch(java.util.concurrent.ExecutionException ignored){}
            }
            parent.check();throw new IOException("Internet check unavailable");
        }finally{for(java.util.concurrent.Future<Boolean> task:tasks)task.cancel(true);probes.close();parent.untrack(probes);executor.shutdownNow();}
    }
    interface Probe {int status(String url)throws Exception;}
    private static final String[] URLS={"https://www.gstatic.com/generate_204","https://cp.cloudflare.com/generate_204"};
    static void verify(Probe probe,RequestScope scope)throws Exception {
        Exception failure=null;
        for(String url:URLS){
            scope.check();
            try{
                int status=probe.status(url);scope.check();
                if(status==204)return;
                failure=new IOException("Internet check did not return the expected response");
            }catch(Exception error){scope.check();failure=error;}
        }
        throw new IOException("Internet check unavailable",failure);
    }
}

