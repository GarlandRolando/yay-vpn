package com.yay.vpn;

import java.net.URI;
import java.util.function.Predicate;

/** Only build-configured HTTPS endpoints can receive account credentials. */
final class ApiRoutes {
    interface Attempt<T> {T send(String base) throws Exception;}
    private final String direct,relay;
    private volatile String preferred;
    ApiRoutes(String direct,String relay){this.direct=valid(direct);this.relay=relay==null||relay.trim().isEmpty()?"":valid(relay);preferred=this.direct;}
    String[] endpoints(){return relay.isEmpty()||relay.equals(direct)?new String[]{direct}:new String[]{direct,relay};}
    static String valid(String value){
        try{
            String text=value.trim();URI u=new URI(text);String host=u.getHost(),path=u.getRawPath();
            if(!"https".equals(u.getScheme())||host==null||!host.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}")||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||u.getPort()!=-1&&u.getPort()!=443||path==null||!path.matches("(?:/[A-Za-z0-9_-]+)*/?"))throw new IllegalArgumentException();
            while(text.endsWith("/"))text=text.substring(0,text.length()-1);return text;
        }catch(Exception e){throw new IllegalArgumentException("Login endpoint must be a plain HTTPS domain and path",e);}
    }
    <T> T call(Attempt<T> attempt,Predicate<Exception> retryable) throws Exception {
        String first=preferred,second=first.equals(direct)?relay:direct;
        try{T result=attempt.send(first);preferred=first;return result;}
        catch(Exception error){if(second.isEmpty()||second.equals(first)||!retryable.test(error))throw error;}
        T result=attempt.send(second);preferred=second;return result;
    }
}
