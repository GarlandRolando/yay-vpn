package com.yay.vpn;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.util.*;

public class ApiRoutesTest {
    static final String DIRECT="https://database.example.com/functions/v1/yay-api",RELAY="https://login.example.com/yay-api";
    @Test public void failedRelayUsesDirectAndRemembersSuccess()throws Exception {
        ApiRoutes routes=new ApiRoutes(DIRECT,RELAY);List<String> calls=new ArrayList<>();
        String result=routes.call(base->{calls.add(base);if(base.equals(RELAY))throw new IOException("network");return "ok";},e->true);
        assertEquals("ok",result);assertEquals(Arrays.asList(RELAY,DIRECT),calls);calls.clear();
        routes.call(base->{calls.add(base);return "ok";},e->true);assertEquals(Collections.singletonList(DIRECT),calls);
    }
    @Test public void serverRejectionIsNotRetried()throws Exception {
        ApiRoutes routes=new ApiRoutes(DIRECT,RELAY);List<String> calls=new ArrayList<>();
        try{routes.call(base->{calls.add(base);throw new IOException("wrong-password");},e->false);fail();}catch(IOException expected){}
        assertEquals(Collections.singletonList(RELAY),calls);
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
