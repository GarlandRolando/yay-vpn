package com.yay.vpn;

import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Per-core metrics. The controller is authenticated, loopback-only, and never persisted. */
final class TunnelTelemetry implements AutoCloseable {
    static final class Rates {
        final long up,down,at;
        Rates(long up,long down){this.up=up;this.down=down;at=SystemClock.elapsedRealtime();}
    }
    static final class Delay {
        final long millis,at;
        Delay(long millis){this.millis=millis;at=SystemClock.elapsedRealtime();}
    }
    private final ScheduledExecutorService workers=Executors.newScheduledThreadPool(2);
    private final Set<HttpURLConnection> connections=ConcurrentHashMap.newKeySet();
    private final String base,secret,delayPath;
    private volatile boolean closed;
    volatile Rates rates;
    volatile Delay delay;

    TunnelTelemetry(JSONObject config)throws Exception {
        int port;
        try(ServerSocket reservation=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=reservation.getLocalPort();}
        byte[] key=new byte[32];new SecureRandom().nextBytes(key);
        secret=android.util.Base64.encodeToString(key,android.util.Base64.NO_WRAP);
        base="http://127.0.0.1:"+port;
        String tag=config.getJSONArray("outbounds").getJSONObject(0).getString("tag");
        delayPath="/proxies/"+URLEncoder.encode(tag,"UTF-8")+"/delay?timeout=4000&url="+URLEncoder.encode("https://www.gstatic.com/generate_204","UTF-8");
        JSONObject experimental=config.optJSONObject("experimental");if(experimental==null){experimental=new JSONObject();config.put("experimental",experimental);}
        experimental.put("clash_api",new JSONObject().put("external_controller","127.0.0.1:"+port).put("secret",secret));
    }
    void start(){workers.scheduleWithFixedDelay(this::readTraffic,0,2,TimeUnit.SECONDS);workers.scheduleWithFixedDelay(this::readDelay,0,15,TimeUnit.SECONDS);}
    private HttpURLConnection open(String path)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(2000);c.setReadTimeout(6000);c.setUseCaches(false);c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Authorization","Bearer "+secret);connections.add(c);
        if(closed){release(c);throw new IOException("Monitor stopped");}return c;
    }
    private void release(HttpURLConnection c){if(c!=null){connections.remove(c);c.disconnect();}}
    private static String line(Reader reader)throws IOException {
        StringBuilder s=new StringBuilder();int ch;
        while((ch=reader.read())!=-1){if(ch=='\n')return s.toString();if(s.length()>=4096)throw new IOException("Oversized metric");s.append((char)ch);}
        return s.length()==0?null:s.toString();
    }
    private void readTraffic(){
        HttpURLConnection c=null;
        try{c=open("/traffic");if(c.getResponseCode()!=200)throw new IOException("Metrics unavailable");
            try(Reader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){
                String line;while(!closed&&(line=line(reader))!=null){JSONObject j=new JSONObject(line);long up=j.getLong("up"),down=j.getLong("down");if(up<0||down<0)throw new IOException("Invalid counters");if(!closed)rates=new Rates(up,down);}
            }
        }catch(Exception ignored){if(!closed)rates=null;}finally{release(c);}
    }
    private void readDelay(){
        HttpURLConnection c=null;
        try{c=open(delayPath);if(c.getResponseCode()!=200)throw new IOException("Latency unavailable");
            try(Reader reader=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8))){long ms=new JSONObject(line(reader)).getLong("delay");if(ms<=0)throw new IOException("Invalid latency");if(!closed)delay=new Delay(ms);}
        }catch(Exception ignored){if(!closed)delay=null;}finally{release(c);}
    }
    @Override public void close(){closed=true;workers.shutdownNow();for(HttpURLConnection c:connections)c.disconnect();connections.clear();rates=null;delay=null;}
}
