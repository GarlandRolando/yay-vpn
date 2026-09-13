package com.yay.vpn;

import org.junit.Test;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class InternetCheckTest {
    @Test public void primarySuccessNeedsOnlyOneProbe()throws Exception {
        List<String> seen=new ArrayList<>();
        InternetCheck.verify(url->{seen.add(url);return 204;},new RequestScope());assertEquals(1,seen.size());
    }
    @Test public void primaryTimeoutCanUseIndependentFallback()throws Exception {
        List<String> seen=new ArrayList<>();
        InternetCheck.verify(url->{seen.add(url);if(seen.size()==1)throw new SocketTimeoutException();return 204;},new RequestScope());
        assertEquals(2,seen.size());assertFalse(new java.net.URL(seen.get(0)).getHost().equals(new java.net.URL(seen.get(1)).getHost()));
    }
    @Test public void redirectIsNotMistakenForInternetSuccess()throws Exception {
        List<String> seen=new ArrayList<>();
        try{InternetCheck.verify(url->{seen.add(url);return 302;},new RequestScope());fail("Redirect accepted");}catch(IOException expected){}
        assertEquals(2,seen.size());
    }
    @Test public void cancellationDoesNotLaunchFallback()throws Exception {
        RequestScope scope=new RequestScope();List<String> seen=new ArrayList<>();
        try{InternetCheck.verify(url->{seen.add(url);scope.cancel();throw new IOException();},scope);fail("Cancelled probe continued");}catch(InterruptedIOException expected){}
        assertEquals(1,seen.size());
    }
    @Test public void cancelledSuccessCannotBeAccepted()throws Exception {
        RequestScope scope=new RequestScope();
        try{InternetCheck.verify(url->{scope.cancel();return 204;},scope);fail("Stale success accepted");}catch(InterruptedIOException expected){}
    }
}
