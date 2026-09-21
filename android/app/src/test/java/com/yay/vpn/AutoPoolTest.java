package com.yay.vpn;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class AutoPoolTest {
    @Test public void diversityAndDeduplication(){
        Map<String,List<String>> groups=new LinkedHashMap<>();groups.put("SG",Arrays.asList("sg1","sg2","sg3","sg1"));groups.put("JP",Arrays.asList("jp1"));groups.put("US",Arrays.asList("us1"));
        for(int seed=0;seed<100;seed++){List<String> order=AutoPool.order(groups,new Random(seed));assertEquals(5,order.size());assertEquals(5,new HashSet<>(order).size());assertTrue(order.subList(0,3).contains("jp1"));assertTrue(order.subList(0,3).contains("us1"));}
        assertTrue(AutoPool.order(Collections.emptyMap(),new Random()).isEmpty());
    }
    private JSONObject config(String host)throws Exception{return new JSONObject("{\"dns\":{\"servers\":[{\"tag\":\"remote\",\"detour\":\"proxy\"}]},\"inbounds\":[{\"type\":\"tun\"}],\"outbounds\":[{\"type\":\"vless\",\"tag\":\"proxy\",\"server\":\""+host+"\",\"server_port\":443}],\"route\":{\"final\":\"proxy\"}}");}
    @Test public void routingAndIsolation()throws Exception{
        JSONObject first=config("one.example"),second=config("two.example");String before=first.toString();
        JSONObject result=AutoPool.build(Arrays.asList(first,second)),group=result.getJSONArray("outbounds").getJSONObject(0);
        assertEquals("yay-auto",group.getString("type"));assertEquals("proxy",group.getString("tag"));assertEquals(2,group.getJSONArray("outbounds").length());
        assertEquals(2,group.getJSONArray("test_urls").length());
        assertEquals(result.getJSONArray("outbounds").getJSONObject(1).getString("tag"),AutoPool.build(Arrays.asList(second,first)).getJSONArray("outbounds").getJSONObject(2).getString("tag"));
        assertEquals(2,AutoPool.build(Arrays.asList(first,first)).getJSONArray("outbounds").length());
        assertEquals(Arrays.asList("c","a","b"),AutoPool.prefer(Arrays.asList("a","b","c"),Arrays.asList("revoked","c","c")));
        assertEquals("proxy",result.getJSONObject("route").getString("final"));assertEquals("proxy",result.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("detour"));
        assertEquals("two.example",result.getJSONArray("outbounds").getJSONObject(2).getString("server"));assertEquals(before,first.toString());
    }
    @Test public void rejectsInvalidPools()throws Exception{
        try{AutoPool.build(Collections.emptyList());fail("empty");}catch(java.io.IOException expected){}
        List<JSONObject> tooMany=new ArrayList<>();for(int i=0;i<13;i++)tooMany.add(config("example"));
        try{AutoPool.build(tooMany);fail("oversized");}catch(java.io.IOException expected){}
        JSONObject bad=config("example");bad.getJSONArray("outbounds").getJSONObject(0).put("detour","proxy");
        try{AutoPool.build(Arrays.asList(bad));fail("recursive");}catch(java.io.IOException expected){}
    }
}
