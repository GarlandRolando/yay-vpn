package com.yay.vpn;
import android.content.Context;
import android.net.*;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class Api {
    static final class Failure extends IOException {final int status;Failure(int n,String s){super(s);status=n;}}
    final SecureStore store;private final Context context;
    Api(Context context) throws Exception {this.context=context.getApplicationContext();store=new SecureStore(context);}
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
    JSONObject call(String method,String path,JSONObject body) throws Exception {
        byte[] bytes=body==null?new byte[0]:body.toString().getBytes(StandardCharsets.UTF_8);
        String timestamp=Long.toString(System.currentTimeMillis()/1000),nonce=UUID.randomUUID().toString();
        String signature=store.sign(method+"\n"+path+"\n"+timestamp+"\n"+nonce+"\n"+SecureStore.sha(bytes));
        Network network=physical(context);if(network==null)throw new IOException("No internet connection. Turn on Wi-Fi or mobile data.");
        HttpURLConnection conn=(HttpURLConnection)network.openConnection(new URL(BuildConfig.API_BASE_URL+path));
        conn.setConnectTimeout(15000);conn.setReadTimeout(15000);conn.setInstanceFollowRedirects(false);conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type","application/json");conn.setRequestProperty("X-Yay-Time",timestamp);conn.setRequestProperty("X-Yay-Nonce",nonce);conn.setRequestProperty("X-Yay-Signature",signature);
        String token=store.get("token");if(!token.isEmpty())conn.setRequestProperty("Authorization","Bearer "+token);
        try {
            if(body!=null){conn.setDoOutput(true);conn.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=conn.getOutputStream()){out.write(bytes);}}
            int code=conn.getResponseCode();InputStream stream=code>=400?conn.getErrorStream():conn.getInputStream();
            byte[] response;try(InputStream in=stream){response=in==null?new byte[0]:readLimited(in);}
            JSONObject result;
            try{result=new JSONObject(new String(response,StandardCharsets.UTF_8));}catch(Exception ex){throw new IOException("The service returned an unexpected response. Please try again.");}
            if(code<200||code>=300)throw new Failure(code,result.optString("error","Could not reach the service."));return result;
        }finally{conn.disconnect();}
    }
    private byte[] readLimited(InputStream in)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
        while((n=in.read(b))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("Response is too large");out.write(b,0,n);}return out.toByteArray();
    }
    JSONObject refresh() throws Exception {
        JSONObject result=call("GET","/v1/bootstrap",null);store.importSeed(result.getString("seed_key"));
        result.remove("seed_key");store.put("bootstrap",result.toString());return result;
    }
}
