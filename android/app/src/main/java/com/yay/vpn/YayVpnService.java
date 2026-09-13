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
    static volatile boolean failed=false;
    private ArrayList<String> candidates=new ArrayList<>();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicLong generation=new AtomicLong();
    private volatile long leaseDeadline=0;
    private BoxService box;private AndroidPlatform platform;private ParcelFileDescriptor tunnel;private ScheduledFuture<?> heartbeat;
    private Api api;private String serverId="";private int revision;
    private final Runnable watchdog=new Runnable(){public void run(){if(leaseDeadline>0&&SystemClock.elapsedRealtime()>=leaseDeadline)requestStop("Access check timed out. Connect again when your internet is available.");else main.postDelayed(this,1000);}};
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("vpn","VPN connection",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if("STOP".equals(intent.getAction())){failed=false;requestStop("Disconnected. Ready when you are.");return START_NOT_STICKY;}
        if(!state.equals("OFF"))return START_NOT_STICKY;
        final long ticket=generation.incrementAndGet();candidates=intent.getStringArrayListExtra("server_ids");if(candidates==null)candidates=new ArrayList<>();
        if(candidates.isEmpty()&&intent.getStringExtra("server_id")!=null)candidates.add(intent.getStringExtra("server_id"));
        serverName=intent.getStringExtra("server_name");failed=false;
        state="CONNECTING";message="Setting up your connection…";showNotification(local("Connecting…","正在连接…","Menghubungkan…"));
        worker.execute(()->startTunnel(ticket));return START_NOT_STICKY;
    }
    private void startTunnel(long ticket){
        Exception failure=null;
        for(String candidate:candidates){
            if(generation.get()!=ticket)return;
            try{
                api=new Api(this);serverId=candidate;long requestAt=SystemClock.elapsedRealtime();
                JSONObject grant=api.call("POST","/v1/connect",new JSONObject().put("server_id",serverId));
                if(generation.get()!=ticket)return;
                leaseDeadline=requestAt+grant.getLong("lease_seconds")*1000;
                if(leaseDeadline<=SystemClock.elapsedRealtime())throw new Exception("Access expired");
                revision=grant.getInt("revision");String config=grant.getJSONObject("config").toString();
                api.store.put("last_config",config);
                SetupOptions options=new SetupOptions();options.setBasePath(getFilesDir().getAbsolutePath());options.setWorkingPath(getFilesDir().getAbsolutePath());options.setTempPath(getCacheDir().getAbsolutePath());options.setFixAndroidStack(true);Libbox.setup(options);
                platform=new AndroidPlatform(this);box=Libbox.newService(config,platform);box.start();
                verifyInternet(ticket);
                if(generation.get()!=ticket){closeCore();return;}
                if(leaseDeadline<=SystemClock.elapsedRealtime())throw new Exception("Access expired");
                state="ON";message="VPN internet access verified.";connectedAt=SystemClock.elapsedRealtime();showNotification(local("Connected · ","已连接 · ","Terhubung · ")+serverName);
                main.removeCallbacks(watchdog);main.post(watchdog);
                heartbeat=worker.scheduleWithFixedDelay(()->renew(ticket),45,45,TimeUnit.SECONDS);return;
            }catch(Exception ex){failure=ex;closeCore();leaseDeadline=0;
                if(ex instanceof Api.Failure&&(((Api.Failure)ex).status==401||((Api.Failure)ex).status==403))break;
            }
        }
        if(generation.get()==ticket){failed=true;requestStop(failure==null?"No server available":friendly(failure));}
    }
    private void verifyInternet(long ticket)throws Exception {
        ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        Network vpn=null;
        for(int attempt=0;attempt<30&&vpn==null;attempt++){
            if(generation.get()!=ticket)throw new Exception("Cancelled");
            for(Network network:cm.getAllNetworks()){
                NetworkCapabilities caps=cm.getNetworkCapabilities(network);LinkProperties link=cm.getLinkProperties(network);
                if(caps==null||link==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;
                for(LinkAddress address:link.getLinkAddresses())if(address.getAddress().getHostAddress().equals("172.19.0.1"))vpn=network;
            }
            if(vpn==null)Thread.sleep(100);
        }
        if(vpn==null)throw new java.io.IOException("VPN network unavailable");
        // Explicit VPN network binding prevents a successful direct request being mistaken for a tunnel test.
        HttpURLConnection connection=(HttpURLConnection)vpn.openConnection(new URL("https://www.gstatic.com/generate_204"));
        connection.setConnectTimeout(6000);connection.setReadTimeout(6000);connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
        try{if(connection.getResponseCode()!=204)throw new java.io.IOException("VPN internet test failed");}
        finally{connection.disconnect();}
    }
    private String local(String en,String zh,String id){String lang=getSharedPreferences("display",0).getString("language","en");return lang.equals("zh")?zh:lang.equals("id")?id:en;}
    private void renew(long ticket){
        if(generation.get()!=ticket||!state.equals("ON"))return;
        try{
            long requestAt=SystemClock.elapsedRealtime();JSONObject grant=api.call("POST","/v1/heartbeat",new JSONObject().put("server_id",serverId).put("revision",revision));
            if(generation.get()==ticket)leaseDeadline=requestAt+grant.getLong("lease_seconds")*1000;
        }catch(Api.Failure ex){requestStop(ex.getMessage());if(ex.status==401||ex.status==403)api.store.clear();}
        catch(Exception ignored){ /* Existing monotonic lease continues; watchdog stops on expiry. */ }
    }
    int openTunnel(TunOptions options)throws Exception{
        Builder builder=new Builder().setSession("Yay VPN").setMtu(options.getMTU());
        if(Build.VERSION.SDK_INT>=29)builder.setMetered(false);
        RoutePrefixIterator v4=options.getInet4Address();while(v4.hasNext()){RoutePrefix p=v4.next();builder.addAddress(p.address(),p.prefix());}
        RoutePrefixIterator v6=options.getInet6Address();while(v6.hasNext()){RoutePrefix p=v6.next();builder.addAddress(p.address(),p.prefix());}
        builder.addRoute("0.0.0.0",0).addRoute("::",0).addDnsServer("172.19.0.2");
        builder.setConfigureIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));
        tunnel=builder.establish();if(tunnel==null)throw new Exception("VPN permission was not granted.");return tunnel.getFd();
    }
    private void showNotification(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,YayVpnService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification n=new android.app.Notification.Builder(this,"vpn").setSmallIcon(R.drawable.ic_shield).setContentTitle("Yay VPN").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new android.app.Notification.Action.Builder(null,local("Disconnect","断开","Putuskan"),stop).build()).build();
        startForeground(11,n);
    }
    private void requestStop(String why){
        generation.incrementAndGet();leaseDeadline=0;main.removeCallbacks(watchdog);state="STOPPING";message=why;
        worker.execute(()->{closeCore();state="OFF";connectedAt=0;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();});
    }
    private void closeCore(){
        if(heartbeat!=null){heartbeat.cancel(false);heartbeat=null;}
        if(box!=null){try{box.close();}catch(Exception ignored){}box=null;}
        if(platform!=null){platform.shutdown();platform=null;}
        if(tunnel!=null){try{tunnel.close();}catch(Exception ignored){}tunnel=null;}
        try{if(api!=null)api.store.put("last_config","");}catch(Exception ignored){}
    }
    private String friendly(Exception ex){
        if(ex instanceof Api.Failure||ex instanceof java.io.IOException)return ex.getMessage();
        return "Could not start this server. Check its settings or choose another server.";
    }
    @Override public void onRevoke(){requestStop("VPN permission was revoked.");super.onRevoke();}
    @Override public void onDestroy(){generation.incrementAndGet();leaseDeadline=0;main.removeCallbacks(watchdog);worker.execute(this::closeCore);worker.shutdown();state="OFF";connectedAt=0;super.onDestroy();}
}
