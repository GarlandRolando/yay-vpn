package com.yay.vpn;

import android.content.Context;
import android.net.Network;
import android.os.*;
import org.json.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real TCP connection latency. Not an authenticated proxy delay or a throughput test. */
final class LatencyProbe {
    static final long MAX_AGE=120000;
    static final class Result {
        final long millis,at;final int revision;final String network;
        Result(long ms,int rev,String n){millis=ms;revision=rev;network=n;at=SystemClock.elapsedRealtime();}
    }
    interface Listener {void progress(int done,int total);void complete();void unauthorized();}
    private final Context context;private final Api api;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor pool=(ThreadPoolExecutor)Executors.newFixedThreadPool(6);
    private final ExecutorService closer=Executors.newSingleThreadExecutor();
    private final AtomicInteger generation=new AtomicInteger();
    private final Map<String,Result> results=new ConcurrentHashMap<>();
    private volatile boolean running;
    private RequestScope active=new RequestScope();
    private Runnable deadline;
    LatencyProbe(Context c,Api a){context=c.getApplicationContext();api=a;}
    boolean isRunning(){return running;}
    private String networkKey(){Network n=Api.physical(context);return n==null?"":n.toString();}
    Result fresh(JSONObject node){Result r=results.get(node.optString("id"));return r!=null&&r.revision==node.optInt("revision")&&r.network.equals(networkKey())&&SystemClock.elapsedRealtime()-r.at<MAX_AGE?r:null;}
    long best(CountryCatalog.Country country){long best=Long.MAX_VALUE;for(JSONObject n:country.nodes){Result r=fresh(n);if(r!=null&&r.millis>=0)best=Math.min(best,r.millis);}return best==Long.MAX_VALUE?-1:best;}
    boolean tested(CountryCatalog.Country country){for(JSONObject n:country.nodes)if(fresh(n)==null)return false;return !country.nodes.isEmpty();}
    List<String> ranked(CountryCatalog.Country country){
        Map<String,Long> measured=new LinkedHashMap<>();
        for(JSONObject n:country.nodes){Result r=fresh(n);if(r!=null)measured.put(n.optString("id"),r.millis);}
        return SelectionPolicy.rank(measured,new java.security.SecureRandom());
    }

    void scan(List<JSONObject> nodes,Listener listener,long budgetMs){
        cancel();int ticket=generation.get();active=new RequestScope();final RequestScope scope=active;running=true;AtomicInteger done=new AtomicInteger();AtomicBoolean denied=new AtomicBoolean();
        if(nodes.isEmpty()){running=false;listener.complete();return;}
        Runnable finish=()->{if(ticket!=generation.get())return;cancel();if(denied.get())listener.unauthorized();else listener.complete();};
        deadline=finish;main.postDelayed(finish,budgetMs);
        for(JSONObject node:nodes)pool.execute(()->{
            if(ticket!=generation.get())return;
            long elapsed=-1;Network network=Api.physical(context);String key=network==null?"":network.toString();
            try{
                JSONObject grant=api.call("POST","/v1/connect",new JSONObject().put("server_id",node.getString("id")),scope,4000);
                if(ticket!=generation.get())return;
                JSONObject outbound=grant.getJSONObject("config").getJSONArray("outbounds").getJSONObject(0);
                if(network!=null){
                    InetAddress[] addresses=network.getAllByName(outbound.getString("server"));
                    if(addresses.length>0){long start=SystemClock.elapsedRealtime();try(Socket socket=network.getSocketFactory().createSocket()){
                        scope.track(socket);
                        try{scope.check();socket.connect(new InetSocketAddress(addresses[0],outbound.getInt("server_port")),1500);elapsed=Math.max(1,SystemClock.elapsedRealtime()-start);}
                        finally{scope.untrack(socket);}
                    }}
                }
            }catch(Api.Failure e){if(e.status==401||e.status==403)denied.set(true);}catch(Exception ignored){}
            if(ticket!=generation.get())return;
            results.put(node.optString("id"),new Result(elapsed,node.optInt("revision"),key));int count=done.incrementAndGet();
            main.post(()->{if(ticket!=generation.get())return;listener.progress(count,nodes.size());if(count==nodes.size()||denied.get())finish.run();});
        });
    }
    void cancel(){generation.incrementAndGet();running=false;pool.getQueue().clear();if(deadline!=null){main.removeCallbacks(deadline);deadline=null;}RequestScope old=active;old.cancel();if(!closer.isShutdown())closer.execute(old::close);}
    void close(){cancel();pool.shutdownNow();closer.shutdown();results.clear();}
}
