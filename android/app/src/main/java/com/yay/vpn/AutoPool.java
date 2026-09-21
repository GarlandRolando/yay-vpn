package com.yay.vpn;
import java.util.*;
import org.json.*;
/** Bounded, country-diverse pools using the upstream URLTest engine. */
final class AutoPool {
    static final int SIZE=12;
    static List<String> order(Map<String,List<String>> countries,Random random){
        List<List<String>> groups=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(List<String> source:countries.values()){
            List<String> group=new ArrayList<>();for(String id:source)if(id!=null&&!id.isEmpty()&&seen.add(id))group.add(id);
            Collections.shuffle(group,random);if(!group.isEmpty())groups.add(group);
        }
        Collections.shuffle(groups,random);List<String> result=new ArrayList<>();
        for(int i=0;;i++){boolean added=false;for(List<String> group:groups)if(i<group.size()){result.add(group.get(i));added=true;}if(!added)break;}
        return result;
    }
    static JSONObject build(List<JSONObject> configs)throws Exception {
        if(configs.isEmpty()||configs.size()>SIZE)throw new java.io.IOException("No authorized Auto servers available");
        JSONObject config=new JSONObject(configs.get(0).toString());JSONArray members=new JSONArray(),outbounds=new JSONArray();
        JSONObject group=new JSONObject().put("type","urltest").put("tag","proxy").put("outbounds",members)
            .put("url","https://cp.cloudflare.com/generate_204").put("interval","30s").put("tolerance",100)
            .put("idle_timeout","30m").put("interrupt_exist_connections",false);
        outbounds.put(group);
        for(int i=0;i<configs.size();i++){
            JSONObject out=new JSONObject(configs.get(i).getJSONArray("outbounds").getJSONObject(0).toString());
            if(!out.optString("tag").equals("proxy")||!out.has("server")||out.has("detour"))throw new java.io.IOException("Unsupported Auto server config");
            String tag="yay-node-"+i;out.put("tag",tag);members.put(tag);outbounds.put(out);
        }
        config.put("outbounds",outbounds);return config;
    }
}
