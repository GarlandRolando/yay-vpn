package com.yay.vpn;

import android.content.Context;
import android.net.*;
import android.os.Build;
import android.system.OsConstants;
import io.nekohasekai.libbox.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.security.KeyStore;

/** Platform bindings for the pinned sing-box 1.12.12 API. */
final class AndroidPlatform implements PlatformInterface {
    private final YayVpnService service;
    private final ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;
    AndroidPlatform(YayVpnService s){service=s;cm=(ConnectivityManager)s.getSystemService(Context.CONNECTIVITY_SERVICE);}
    @Override public LocalDNSTransport localDNSTransport(){return null;}
    @Override public boolean usePlatformAutoDetectInterfaceControl(){return true;}
    @Override public void autoDetectInterfaceControl(int fd)throws Exception{if(!service.protect(fd))throw new Exception("Could not protect VPN socket");}
    @Override public int openTun(TunOptions options)throws Exception{return service.openTunnel(options);}
    @Override public void writeLog(String message){ /* Avoid server addresses and credentials in logcat. */ }
    @Override public boolean useProcFS(){return false;}
    @Override public int findConnectionOwner(int protocol,String source,int sourcePort,String dest,int destPort)throws Exception{
        if(Build.VERSION.SDK_INT<29)throw new Exception("Connection ownership unavailable");
        return cm.getConnectionOwnerUid(protocol,new InetSocketAddress(source,sourcePort),new InetSocketAddress(dest,destPort));
    }
    @Override public String packageNameByUid(int uid){String[] names=service.getPackageManager().getPackagesForUid(uid);return names==null||names.length==0?"":names[0];}
    @Override public int uidByPackageName(String name)throws Exception{return service.getPackageManager().getApplicationInfo(name,0).uid;}
    @Override public synchronized void startDefaultInterfaceMonitor(InterfaceUpdateListener listener){
        if(callback!=null)cm.unregisterNetworkCallback(callback);
        callback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network n){update(listener);}
            @Override public void onLost(Network n){update(listener);}
            @Override public void onLinkPropertiesChanged(Network n,LinkProperties p){update(listener);}
            @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){update(listener);}
        };
        cm.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),callback);
        update(listener);
    }
    private void update(InterfaceUpdateListener listener){
        Network network=Api.physical(service);
        try{
            LinkProperties link=network==null?null:cm.getLinkProperties(network);NetworkCapabilities caps=network==null?null:cm.getNetworkCapabilities(network);
            java.net.NetworkInterface iface=link==null?null:java.net.NetworkInterface.getByName(link.getInterfaceName());
            if(iface==null){listener.updateDefaultInterface("",-1,false,false);return;}
            service.setUnderlyingNetworks(new Network[]{network});
            listener.updateDefaultInterface(iface.getName(),iface.getIndex(),caps!=null&&!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),false);
        }catch(Exception ignored){listener.updateDefaultInterface("",-1,false,false);}
    }
    @Override public synchronized void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener){shutdown();}
    synchronized void shutdown(){if(callback!=null){try{cm.unregisterNetworkCallback(callback);}catch(Exception ignored){}callback=null;}}
    @Override public NetworkInterfaceIterator getInterfaces()throws Exception{
        List<io.nekohasekai.libbox.NetworkInterface> result=new ArrayList<>();
        for(Network n:cm.getAllNetworks()){
            NetworkCapabilities caps=cm.getNetworkCapabilities(n);LinkProperties link=cm.getLinkProperties(n);
            if(caps==null||link==null||link.getInterfaceName()==null||!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))continue;
            java.net.NetworkInterface system=java.net.NetworkInterface.getByName(link.getInterfaceName());if(system==null)continue;
            io.nekohasekai.libbox.NetworkInterface item=new io.nekohasekai.libbox.NetworkInterface();
            item.setName(system.getName());item.setIndex(system.getIndex());item.setMTU(system.getMTU());
            List<String> addresses=new ArrayList<>();for(LinkAddress a:link.getLinkAddresses())addresses.add(a.toString());item.setAddresses(new Strings(addresses));
            List<String> dns=new ArrayList<>();for(java.net.InetAddress a:link.getDnsServers())dns.add(a.getHostAddress());item.setDNSServer(new Strings(dns));
            int flags=OsConstants.IFF_UP|OsConstants.IFF_RUNNING;if(system.isLoopback())flags|=OsConstants.IFF_LOOPBACK;if(system.isPointToPoint())flags|=OsConstants.IFF_POINTOPOINT;if(system.supportsMulticast())flags|=OsConstants.IFF_MULTICAST;item.setFlags(flags);
            item.setType(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?Libbox.InterfaceTypeWIFI:caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?Libbox.InterfaceTypeCellular:caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)?Libbox.InterfaceTypeEthernet:Libbox.InterfaceTypeOther);
            item.setMetered(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));result.add(item);
        }
        Iterator<io.nekohasekai.libbox.NetworkInterface> iterator=result.iterator();
        return new NetworkInterfaceIterator(){public boolean hasNext(){return iterator.hasNext();}public io.nekohasekai.libbox.NetworkInterface next(){return iterator.next();}};
    }
    @Override public boolean underNetworkExtension(){return false;}
    @Override public boolean includeAllNetworks(){return false;}
    @Override public WIFIState readWIFIState(){return null;}
    @Override public StringIterator systemCertificates(){
        List<String> certs=new ArrayList<>();
        try{KeyStore ks=KeyStore.getInstance("AndroidCAStore");ks.load(null);Enumeration<String> names=ks.aliases();while(names.hasMoreElements()){
            String name=names.nextElement();if(!name.startsWith("system:"))continue;
            certs.add("-----BEGIN CERTIFICATE-----\n"+android.util.Base64.encodeToString(ks.getCertificate(name).getEncoded(),android.util.Base64.DEFAULT)+"-----END CERTIFICATE-----\n");
        }}catch(Exception ignored){}
        return new Strings(certs);
    }
    @Override public void clearDNSCache(){}
    @Override public void sendNotification(io.nekohasekai.libbox.Notification notification){}
    static final class Strings implements StringIterator {
        private final List<String> list;private int at=0;Strings(List<String> l){list=l;}
        public int len(){return list.size()-at;}public boolean hasNext(){return at<list.size();}public String next(){return hasNext()?list.get(at++):"";}
    }
}
