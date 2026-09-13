package com.yay.vpn;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Owns network resources for one operation; a cancelled operation cannot add work. */
final class RequestScope implements AutoCloseable {
    private final Set<AutoCloseable> resources=new HashSet<>();
    private volatile boolean cancelled;
    boolean isCancelled(){return cancelled;}
    synchronized void cancel(){cancelled=true;}
    void check()throws InterruptedIOException {
        if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Cancelled");
    }
    void track(AutoCloseable resource)throws InterruptedIOException {
        synchronized(this){if(!cancelled){resources.add(resource);return;}}
        release(resource);throw new InterruptedIOException("Cancelled");
    }
    synchronized void untrack(AutoCloseable resource){resources.remove(resource);}
    @Override public void close(){
        ArrayList<AutoCloseable> pending;
        synchronized(this){cancelled=true;pending=new ArrayList<>(resources);resources.clear();}
        for(AutoCloseable resource:pending)release(resource);
    }
    private static void release(AutoCloseable resource){try{resource.close();}catch(Exception ignored){}}
}
