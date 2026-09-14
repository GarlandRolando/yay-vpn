package com.yay.vpn;
import android.app.*;
import android.content.Context;
import android.net.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

final class Api {
    static final class Failure extends IOException {final int status;Failure(int n,String s){super(s);status=n;}}
    static final class NoNetwork extends IOException {}
    static final class UnexpectedResponse extends IOException {}
    final SecureStore store;private final Context context;private final Activity activity;
    Api(Context context) throws Exception {this.context=context.getApplicationContext();activity=context instanceof Activity?(Activity)context:null;store=new SecureStore(context);}
    static Network physical(Context c) {
        ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network active=cm.getActiveNetwork();Network fallback=null;
        for(Network n:cm.getAllNetworks()) {
            NetworkCapabilities caps=cm.getNetworkCapabilities(n);
            if(caps==null||!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)||!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))continue;
            if(n.equals(active))return n;
            if(fallback==null||caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))fallback=n;
        }
        return fallback;
    }
    static Network tunnelNetwork(Context context) {
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        for(Network network:cm.getAllNetworks()){
            NetworkCapabilities caps=cm.getNetworkCapabilities(network);LinkProperties link=cm.getLinkProperties(network);
            if(caps==null||link==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;
            for(LinkAddress address:link.getLinkAddresses())if("172.19.0.1".equals(address.getAddress().getHostAddress()))return network;
        }
        return null;
    }
    private Network backendNetwork() {
        // Once established, API traffic may use our tunnel like any other app traffic.
        // Only the core's upstream sockets need to bypass the VPN to avoid a loop.
        if("ON".equals(YayVpnService.state)){Network vpn=tunnelNetwork(context);if(vpn!=null)return vpn;}
        // Before startup there is no working Yay tunnel available for authorization.
        if(!"OFF".equals(YayVpnService.state))return physical(context);
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.getActiveNetwork();
    }
    JSONObject call(String method,String path,JSONObject body) throws Exception {
        return call(method,path,body,null,15000);
    }
    JSONObject call(String method,String path,JSONObject body,RequestScope scope,int timeoutMs) throws Exception {
        if(scope!=null)scope.check();
        byte[] bytes=body==null?new byte[0]:body.toString().getBytes(StandardCharsets.UTF_8);
        String timestamp=Long.toString(System.currentTimeMillis()/1000),nonce=UUID.randomUUID().toString();
        String signature=store.sign(method+"\n"+path+"\n"+timestamp+"\n"+nonce+"\n"+SecureStore.sha(bytes));
        Network network=backendNetwork();if(network==null)throw new NoNetwork();
        HttpURLConnection conn=(HttpURLConnection)network.openConnection(new URL(BuildConfig.API_BASE_URL+path));
        conn.setConnectTimeout(timeoutMs);conn.setReadTimeout(timeoutMs);conn.setInstanceFollowRedirects(false);conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type","application/json");conn.setRequestProperty("X-Yay-Time",timestamp);conn.setRequestProperty("X-Yay-Nonce",nonce);conn.setRequestProperty("X-Yay-Signature",signature);
        String token=store.get("token");if(!token.isEmpty())conn.setRequestProperty("Authorization","Bearer "+token);
        AutoCloseable abort=conn::disconnect;
        try {
            if(scope!=null){scope.track(abort);scope.check();}
            if(body!=null){conn.setDoOutput(true);conn.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=conn.getOutputStream()){out.write(bytes);}}
            int code=conn.getResponseCode();InputStream stream=code>=400?conn.getErrorStream():conn.getInputStream();
            byte[] response;try(InputStream in=stream){response=in==null?new byte[0]:readLimited(in);}
            JSONObject result;
            try{result=new JSONObject(new String(response,StandardCharsets.UTF_8));}catch(org.json.JSONException ex){
                if(code<200||code>=300)throw new Failure(code,"The service returned an HTTP error.");
                throw new UnexpectedResponse();
            }
            if(scope!=null)scope.check();
            if(code<200||code>=300)throw new Failure(code,result.optString("error","Could not reach the service."));
            if("POST".equals(method)&&"/v1/login".equals(path)&&result.optBoolean("requires_device_replacement")) {
                String replacement=chooseReplacement(result.optJSONArray("devices"));
                if(replacement==null)throw new Failure(409,t("Device limit reached. Choose a device to replace or cancel sign-in.","已达到设备上限。请选择要替换的设备，或取消登录。","Batas perangkat tercapai. Pilih perangkat yang akan diganti atau batalkan masuk."));
                JSONObject retry=body==null?new JSONObject():new JSONObject(body.toString());retry.put("replace_device_id",replacement);
                return call(method,path,retry,scope,timeoutMs);
            }
            return result;
        }finally{if(scope!=null)scope.untrack(abort);conn.disconnect();}
    }
    private String chooseReplacement(JSONArray devices)throws Exception {
        if(activity==null||activity.isFinishing()||devices==null||devices.length()==0)return null;
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new Failure(409,t("Device replacement must be opened from the sign-in screen.","请从登录界面进行设备替换。","Penggantian perangkat harus dibuka dari layar masuk."));
        String[] labels=new String[devices.length()];String[] ids=new String[devices.length()];
        DateFormat format=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT);
        for(int i=0;i<devices.length();i++){
            JSONObject item=devices.optJSONObject(i);if(item==null)continue;ids[i]=item.optString("id");String name=item.optString("name",t("Unnamed device","未命名设备","Perangkat tanpa nama"));
            long seen=item.optLong("last_seen");labels[i]=name+(seen>0?"\n"+t("Last used: ","最近使用：","Terakhir digunakan: ")+format.format(new Date(seen*1000)):"");
        }
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> selected=new AtomicReference<>();
        activity.runOnUiThread(()->{
            final int[] choice={-1};
            AlertDialog dialog=new AlertDialog.Builder(activity)
              .setTitle(t("Device limit reached","已达到设备上限","Batas perangkat tercapai"))
              .setMessage(t("Choose one registered device to sign out. The selected device loses access immediately, then this device signs in.","请选择一台已注册设备并将其退出登录。所选设备会立即失去访问权限，然后当前设备登录。","Pilih satu perangkat terdaftar untuk dikeluarkan. Perangkat tersebut langsung kehilangan akses, lalu perangkat ini masuk."))
              .setSingleChoiceItems(labels,-1,(d,which)->choice[0]=which)
              .setNegativeButton(t("Cancel","取消","Batal"),(d,w)->done.countDown())
              .setPositiveButton(t("Replace device","替换设备","Ganti perangkat"),null)
              .create();
            dialog.setOnCancelListener(d->done.countDown());
            dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                int which=choice[0];if(which<0||which>=ids.length||ids[which]==null||ids[which].isEmpty())return;
                selected.set(ids[which]);dialog.dismiss();done.countDown();
            }));
            dialog.show();
        });
        if(!done.await(10,TimeUnit.MINUTES))return null;return selected.get();
    }
    private String t(String en,String zh,String id){String lang=context.getSharedPreferences("display",0).getString("language","en");return lang.equals("zh")?zh:lang.equals("id")?id:en;}
    private byte[] readLimited(InputStream in)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
        while((n=in.read(b))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("Response is too large");out.write(b,0,n);}return out.toByteArray();
    }
    JSONObject refresh() throws Exception {
        JSONObject result=call("GET","/v1/bootstrap",null);store.importSeed(result.getString("seed_key"));
        result.remove("seed_key");store.put("bootstrap",result.toString());return result;
    }
}
