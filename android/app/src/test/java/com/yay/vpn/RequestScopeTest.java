package com.yay.vpn;

import org.junit.Test;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RequestScopeTest {
    @Test public void cancellationRejectsLateRequestsAndClosesPendingResources()throws Exception {
        RequestScope scope=new RequestScope();AtomicInteger closes=new AtomicInteger();
        AutoCloseable pending=()->closes.incrementAndGet();scope.track(pending);
        scope.cancel();assertTrue(scope.isCancelled());
        try{scope.check();fail("Cancelled request accepted");}catch(InterruptedIOException expected){}
        try{scope.track(()->closes.incrementAndGet());fail("Late connection accepted");}catch(InterruptedIOException expected){}
        assertEquals(1,closes.get());scope.close();scope.close();assertEquals(2,closes.get());
    }
    @Test public void completedResourcesAreNotClosedAgain()throws Exception {
        RequestScope scope=new RequestScope();AtomicInteger closes=new AtomicInteger();
        AutoCloseable done=()->closes.incrementAndGet();scope.track(done);scope.untrack(done);scope.close();
        assertEquals(0,closes.get());
    }
    @Test public void cancellationRacingRegistrationNeverLeaksAResource()throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{for(int i=0;i<100;i++){
            RequestScope scope=new RequestScope();AtomicInteger closes=new AtomicInteger();CountDownLatch go=new CountDownLatch(1);
            Future<?> add=pool.submit(()->{try{go.await();scope.track(()->closes.incrementAndGet());}catch(InterruptedIOException expected){}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}});
            Future<?> cancel=pool.submit(()->{try{go.await();scope.close();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}});
            go.countDown();add.get(2,TimeUnit.SECONDS);cancel.get(2,TimeUnit.SECONDS);scope.close();assertEquals(1,closes.get());
        }}finally{pool.shutdownNow();}
    }
    @Test public void failedResourceCloseDoesNotPreventOtherCleanup()throws Exception {
        RequestScope scope=new RequestScope();AtomicInteger closes=new AtomicInteger();
        scope.track(()->{throw new Exception("Mock close failure");});scope.track(()->closes.incrementAndGet());scope.close();assertEquals(1,closes.get());
    }
    @Test public void cancellationUnblocksPendingSocketRead()throws Exception {
        ExecutorService pool=Executors.newSingleThreadExecutor();RequestScope scope=new RequestScope();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
            Socket client=new Socket("127.0.0.1",server.getLocalPort());Socket peer=server.accept()){
            client.setSoTimeout(5000);scope.track(client);CountDownLatch reading=new CountDownLatch(1);
            Future<Boolean> result=pool.submit(()->{reading.countDown();try{return client.getInputStream().read()==-1;}catch(IOException expected){return true;}});
            assertTrue(reading.await(2,TimeUnit.SECONDS));scope.close();assertTrue(result.get(2,TimeUnit.SECONDS));assertTrue(client.isClosed());
        }finally{scope.close();pool.shutdownNow();}
    }
}
