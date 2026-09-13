package com.yay.vpn;

import android.app.*;
import android.content.*;
import android.net.*;
import java.net.*;
import java.util.*;
import android.os.*;
import io.nekohasekai.libbox.*;
import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class YayVpnService extends VpnService {
    static volatile String state="OFF",message="Ready when you are.",serverName="";
    static volatile long connectedAt=0;
    static volatile boolean internetHealthy=false;
    static volatile TunnelTelemetry telemetry;
    static volatile boolean failed=false;
    private ArrayList<String> candidates=new ArrayList<>();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService healthWorker=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService cancellationWorker=Executors.newSingleThreadExecutor();
    private final Object lifecycle=new Object();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicLong generation=new AtomicLong();
    private volatile long leaseDeadline=0;
    private volatile long connectDeadline=0;
    private volatile RequestScope requests=new RequestScope();
    private volatile boolean destroyed;
    private Future<?> starting;
    private ScheduledFuture<?> healthCheck;
    private BoxService box;private AndroidPlatform platform;private ParcelFileDescriptor tunnel;private ScheduledFuture<?> heartbeat;
    private Api api;private String serverId="";private int revision;
    private final Runnable watchdog=new Runnable(){public void run(){
        long now=SystemClock.elapsedRealtime();
        if(state.equals("CONNECTING")&&connectDeadline>0&&now>=connectDeadline){failed=true;requestStop("Connection timed out. Try another country or network.");}
        else if(leaseDeadline>0&&now>=leaseDeadline){failed=true;requestStop("Access check timed out. Connect again when your internet is available.");}
        // Reachability samples are advisory. A blocked probe host must not stop traffic.
        else if(!destroyed&&!state.equals("OFF")&&!state.equals("STOPPING"))main.postDelayed(this,1000);
    }};
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("vpn","VPN connection",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if("STOP".equals(intent.getAction())){failed=false;requestStop("Disconnected. Ready when you are.");return START_NOT_STICKY;}
        if(!state.equals("OFF"))return START_NOT_STICKY;
        final long ticket=generation.incrementAndGet();candidates=intent.getStringArrayListExtra("server_ids");if(candidates==null)candidates=new ArrayList<>();
        if(candidates.isEmpty()&&intent.getStringExtra("server_id")!=null)candidates.add(intent.getStringExtra("server_id"));
        serverName=intent.getStringExtra("server_name");failed=false;internetHealthy=false;requests=new RequestScope();
        state="CONNECTING";connectDeadline=SystemClock.elapsedRealtime()+45000;message="Setting up your connection…";showNotification(local("Connecting…","正在连接…","Menghubungkan…"));
        main.removeCallbacks(watchdog);main.post(watchdog);
        starting=worker.submit(()->startTunnel(ticket));return START_NOT_STICKY;
    }
    private void startTunnel(long ticket){
        Exception failure=null;
        for(String candidate:candidates){
            if(generation.get()!=ticket)return;
            try{
                api=new Api(this);serverId=candidate;long requestAt=SystemClock.elapsedRealtime();
                JSONObject grant=api.call("POST","/v1/connect",new JSONObject().put("server_id",serverId),requests,7000);
                if(generation.get()!=ticket)return;
                leaseDeadline=requestAt+grant.getLong("lease_seconds")*1000;
                if(leaseDeadline<=SystemClock.elapsedRealtime())throw new Exception("Access expired");
                revision=grant.getInt("revision");JSONObject coreConfig=grant.getJSONObject("config");
                telemetry=new TunnelTelemetry(coreConfig);String config=coreConfig.toString();
                SetupOptions options=new SetupOptions();options.setBasePath(getFilesDir().getAbsolutePath());options.setWorkingPath(getFilesDir().getAbsolutePath());options.setTempPath(getCacheDir().getAbsolutePath());options.setFixAndroidStack(true);Libbox.setup(options);
                requests.check();platform=new AndroidPlatform(this);box=Libbox.newService(config,platform);requests.check();box.start();
                verifyInternet(ticket);
                if(generation.get()!=ticket){closeCore();return;}
                if(leaseDeadline<=SystemClock.elapsedRealtime())throw new Exception("Access expired");
                synchronized(lifecycle){
                    if(generation.get()!=ticket||requests.isCancelled())return;
                    state="ON";internetHealthy=true;message="VPN internet access verified.";connectedAt=SystemClock.elapsedRealtime();connectDeadline=0;
                }
                showNotification(local("Connected · ","已连接 · ","Terhubung · ")+serverName);
                healthCheck=healthWorker.scheduleWithFixedDelay(()->checkHealth(ticket),15,15,TimeUnit.SECONDS);
                telemetry.start();heartbeat=worker.scheduleWithFixedDelay(()->renew(ticket),45,45,TimeUnit.SECONDS);return;
            }catch(Exception ex){failure=ex;closeCore();leaseDeadline=0;
                if(generation.get()!=ticket||requests.isCancelled())return;
                if(ex instanceof Api.Failure&&(((Api.Failure)ex).status==401||((Api.Failure)ex).status==403))break;
            }
        }
        if(generation.get()==ticket){failed=true;requestStop(failure==null?"No server available":friendly(failure));}
    }
    private void verifyInternet(long ticket)throws Exception {
        requests.check();
        Network vpn=null;
        for(int attempt=0;attempt<30&&vpn==null;attempt++){
            if(generation.get()!=ticket)throw new Exception("Cancelled");
            vpn=Api.tunnelNetwork(this);
            if(vpn==null)Thread.sleep(100);
        }
        if(vpn==null)throw new java.io.IOException("VPN network unavailable");
        // Explicit VPN network binding prevents a successful direct request being mistaken for a tunnel test.
        final Network tunnelNetwork=vpn;final RequestScope scope=requests;
        InternetCheck.verify(url->{
            HttpURLConnection connection=(HttpURLConnection)tunnelNetwork.openConnection(new URL(url));
            connection.setConnectTimeout(4000);connection.setReadTimeout(4000);connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            AutoCloseable abort=connection::disconnect;
            try{scope.track(abort);scope.check();return connection.getResponseCode();}
            finally{scope.untrack(abort);connection.disconnect();}
        },scope);
    }
    private void checkHealth(long ticket){
        if(generation.get()!=ticket||!state.equals("ON"))return;
        try{
            verifyInternet(ticket);
            boolean recovered;
            synchronized(lifecycle){if(generation.get()!=ticket||!state.equals("ON"))return;recovered=!internetHealthy;internetHealthy=true;}
            if(recovered)main.post(()->{if(generation.get()==ticket&&state.equals("ON"))showNotification(local("Connected · ","已连接 · ","Terhubung · ")+serverName);});
        }catch(Exception ignored){
            synchronized(lifecycle){if(generation.get()!=ticket||!state.equals("ON"))return;internetHealthy=false;}
            main.post(()->{if(generation.get()==ticket&&state.equals("ON")&&!internetHealthy)showNotification(local("VPN active · internet check unavailable","VPN 已启动 · 网络检查不可用","VPN aktif · pemeriksaan internet tidak tersedia"));});
        }
    }
    private String local(String en,String zh,String id){String lang=getSharedPreferences("display",0).getString("language","en");return lang.equals("zh")?zh:lang.equals("id")?id:en;}
    private void renew(long ticket){
        if(generation.get()!=ticket||!state.equals("ON"))return;
        try{
            long requestAt=SystemClock.elapsedRealtime();JSONObject grant=api.call("POST","/v1/heartbeat",new JSONObject().put("server_id",serverId).put("revision",revision),requests,7000);
            if(generation.get()==ticket)leaseDeadline=requestAt+grant.getLong("lease_seconds")*1000;
        }catch(Api.Failure ex){
            if(generation.get()!=ticket)return;
            // Retry temporary errors on the next heartbeat without extending the lease.
            if(ex.status==408||ex.status==425||ex.status==429||(ex.status>=500&&ex.status<=599))return;
            failed=true;requestStop(ex.getMessage());if(ex.status==401||ex.status==403)api.store.clear();
        }
        catch(Exception ignored){ /* Existing monotonic lease continues; watchdog stops on expiry. */ }
    }
    int openTunnel(TunOptions options)throws Exception{
        requests.check();
        Builder builder=new Builder().setSession("Yay VPN").setMtu(options.getMTU());
        if(Build.VERSION.SDK_INT>=29)builder.setMetered(false);
        RoutePrefixIterator v4=options.getInet4Address();while(v4.hasNext()){RoutePrefix p=v4.next();builder.addAddress(p.address(),p.prefix());}
        RoutePrefixIterator v6=options.getInet6Address();while(v6.hasNext()){RoutePrefix p=v6.next();builder.addAddress(p.address(),p.prefix());}
        builder.addRoute("0.0.0.0",0).addRoute("::",0).addDnsServer("172.19.0.2");
        builder.setConfigureIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));
        ParcelFileDescriptor established=builder.establish();if(established==null)throw new Exception("VPN permission was not granted.");
        synchronized(lifecycle){
            if(destroyed||requests.isCancelled()||!state.equals("CONNECTING")){established.close();throw new java.io.IOException("Connection cancelled");}
            tunnel=established;return tunnel.getFd();
        }
    }
    private void showNotification(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,YayVpnService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification n=new android.app.Notification.Builder(this,"vpn").setSmallIcon(R.drawable.ic_shield).setContentTitle("Yay VPN").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new android.app.Notification.Action.Builder(null,local("Disconnect","断开","Putuskan"),stop).build()).build();
        startForeground(11,n);
    }
    private void requestStop(String why){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->requestStop(why));return;}
        // STOP must invalidate startup immediately, not sit behind a blocked HTTP call.
        synchronized(lifecycle){
            if(destroyed||state.equals("STOPPING"))return;
            generation.incrementAndGet();requests.cancel();leaseDeadline=0;connectDeadline=0;internetHealthy=false;connectedAt=0;state="STOPPING";message=why;
        }
        main.removeCallbacks(watchdog);closeTunnel();
        RequestScope cancelled=requests;cancellationWorker.execute(cancelled::close);
        if(starting!=null)starting.cancel(true);
        if(healthCheck!=null)healthCheck.cancel(true);
        worker.execute(()->{closeCore();main.post(()->{if(destroyed)return;state="OFF";stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});});
    }
    private void closeTunnel(){
        ParcelFileDescriptor old;synchronized(lifecycle){old=tunnel;tunnel=null;}
        if(old!=null)try{old.close();}catch(Exception ignored){}
    }
    private void closeCore(){
        TunnelTelemetry old=telemetry;telemetry=null;
        if(heartbeat!=null){heartbeat.cancel(false);heartbeat=null;}
        if(healthCheck!=null){healthCheck.cancel(true);healthCheck=null;}
        if(box!=null){try{box.close();}catch(Exception ignored){}box=null;}
        if(old!=null)old.close();
        if(platform!=null){platform.shutdown();platform=null;}
        closeTunnel();
        try{if(api!=null)api.store.put("last_config","");}catch(Exception ignored){}
    }
    private String friendly(Exception ex){
        if(ex instanceof Api.NoNetwork)return "No internet connection. Enable Wi-Fi or mobile data.";
        if(ex instanceof java.net.SocketTimeoutException)return "Connection timed out. Try another country or network.";
        if(ex instanceof java.net.UnknownHostException)return "Could not resolve the service or test address. Try another network.";
        if(ex instanceof Api.Failure)return "Access check failed (HTTP "+((Api.Failure)ex).status+"). Sign in again or contact support.";
        if(ex instanceof java.io.IOException)return "VPN internet test failed. Try another country or network.";
        return "Could not start this server. Check its settings or choose another server.";
    }
    @Override public void onRevoke(){requestStop("VPN permission was revoked.");super.onRevoke();}
    @Override public void onDestroy(){
        synchronized(lifecycle){destroyed=true;generation.incrementAndGet();requests.cancel();leaseDeadline=0;connectDeadline=0;internetHealthy=false;}
        main.removeCallbacks(watchdog);closeTunnel();if(starting!=null)starting.cancel(true);
        RequestScope cancelled=requests;cancellationWorker.execute(cancelled::close);cancellationWorker.shutdown();healthWorker.shutdownNow();
        worker.execute(this::closeCore);worker.shutdown();state="OFF";connectedAt=0;super.onDestroy();
    }
}
