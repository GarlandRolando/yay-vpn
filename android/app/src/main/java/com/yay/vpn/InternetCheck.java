package com.yay.vpn;

import java.io.IOException;

/** A reachability sample, not proof that all internet traffic is healthy. */
final class InternetCheck {
    interface Probe {int status(String url)throws Exception;}
    private static final String[] URLS={"https://www.gstatic.com/generate_204","https://cp.cloudflare.com/generate_204"};
    static void verify(Probe probe,RequestScope scope)throws Exception {
        Exception failure=null;
        for(String url:URLS){
            scope.check();
            try{
                int status=probe.status(url);scope.check();
                if(status==204)return;
                failure=new IOException("Internet check did not return the expected response");
            }catch(Exception error){scope.check();failure=error;}
        }
        throw new IOException("Internet check unavailable",failure);
    }
}
