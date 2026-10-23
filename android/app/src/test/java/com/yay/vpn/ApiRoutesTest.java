package com.yay.vpn;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.*;

public class ApiRoutesTest {
    static final String DIRECT="https://database.example.com/functions/v1/yay-api",RELAY="https://login.example.com/yay-api";
    @Test public void failedDirectUsesRelayAndRemembersSuccess()throws Exception {
        ApiRoutes routes=new ApiRoutes(DIRECT,RELAY);List<String> calls=new ArrayList<>();
        String result=routes.call(base->{calls.add(base);if(base.equals(DIRECT))throw new IOException("network");return "ok";},e->true);
        assertEquals("ok",result);assertEquals(Arrays.asList(DIRECT,RELAY),calls);calls.clear();
        routes.call(base->{calls.add(base);return "ok";},e->true);assertEquals(Collections.singletonList(RELAY),calls);
        calls.clear();
        routes.call(base->{calls.add(base);if(base.equals(RELAY))throw new IOException("relay lost");return "ok";},e->true);
        assertEquals(Arrays.asList(RELAY,DIRECT),calls);
    }
    @Test public void reachableOriginalBackendDoesNotWaitForRelay()throws Exception {
        ApiRoutes routes=new ApiRoutes(DIRECT,RELAY);List<String> calls=new ArrayList<>();
        routes.call(base->{calls.add(base);if(base.equals(RELAY))throw new AssertionError("Unneeded relay request");return "ok";},e->true);
        assertEquals(Collections.singletonList(DIRECT),calls);
    }
    @Test public void serverRejectionIsNotRetried()throws Exception {
        ApiRoutes routes=new ApiRoutes(DIRECT,RELAY);List<String> calls=new ArrayList<>();
        try{routes.call(base->{calls.add(base);throw new IOException("wrong-password");},e->false);fail();}catch(IOException expected){}
        assertEquals(Collections.singletonList(DIRECT),calls);
    }
    @Test public void sameAddressIsNotRetriedAndDiagnosticsDeduplicate()throws Exception {
        ApiRoutes routes=new ApiRoutes(DIRECT,DIRECT+"/");List<String> calls=new ArrayList<>();
        try{routes.call(base->{calls.add(base);throw new IOException("network");},e->true);fail();}catch(IOException expected){}
        assertEquals(Collections.singletonList(DIRECT),calls);
        assertEquals(Collections.singletonList(DIRECT),Arrays.asList(routes.endpoints()));
        assertEquals(Arrays.asList(DIRECT,RELAY),Arrays.asList(new ApiRoutes(DIRECT,RELAY).endpoints()));
    }
    @Test public void unreachableAddressesAreTriedOnlyOnce()throws Exception {
        ApiRoutes routes=new ApiRoutes(DIRECT,RELAY);List<String> calls=new ArrayList<>();
        try{routes.call(base->{calls.add(base);throw new IOException(base);},e->true);fail();}
        catch(IOException expected){assertEquals(RELAY,expected.getMessage());}
        assertEquals(Arrays.asList(DIRECT,RELAY),calls);
    }
    @Test public void noRelayDoesNotRepeatRequests()throws Exception {
        ApiRoutes routes=new ApiRoutes(DIRECT,"");List<String> calls=new ArrayList<>();
        try{routes.call(base->{calls.add(base);throw new IOException();},e->true);fail();}catch(IOException expected){}
        assertEquals(Collections.singletonList(DIRECT),calls);
    }
    @Test public void invalidCredentialDestinationsAreRejected(){
        for(String bad:Arrays.asList("http://login.example.com/yay-api","https://user:pass@login.example.com/yay-api","https://login.example.com/yay-api?q=1","https://login.example.com/yay-api#x","https://login.example.com:8443/yay-api","https://login.example.com/../other","https://login.example.com/%2e%2e/other","https://127.0.0.1/yay-api","https://login.example.com//yay-api"))try{new ApiRoutes(DIRECT,bad);fail(bad);}catch(IllegalArgumentException expected){}
        assertEquals(RELAY,ApiRoutes.valid(RELAY+"/"));
    }
}
