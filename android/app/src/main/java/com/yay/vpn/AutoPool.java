package com.yay.vpn;
import java.util.*;
import org.json.*;
/** Bounded, country-diverse pools using the shared Yay Auto monitor. */
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
    static List<String> prefer(List<String> order,List<String> previous){
        Set<String> allowed=new HashSet<>(order);LinkedHashSet<String> result=new LinkedHashSet<>();
        for(String id:previous)if(allowed.contains(id))result.add(id);result.addAll(order);return new ArrayList<>(result);
    }
    static JSONObject build(List<JSONObject> configs)throws Exception {return build(configs,"");}
    static JSONObject build(List<JSONObject> configs,String historyPath)throws Exception {
        if(configs.isEmpty()||configs.size()>SIZE)throw new java.io.IOException("No authorized Auto servers available");
        JSONObject config=new JSONObject(configs.get(0).toString());JSONArray members=new JSONArray(),outbounds=new JSONArray();Set<String> seen=new HashSet<>();
        JSONObject group=new JSONObject().put("type","yay-auto").put("tag","proxy").put("outbounds",members)
            .put("test_urls",new JSONArray(Arrays.asList("https://cp.cloudflare.com/generate_204","https://www.gstatic.com/generate_204"))).put("history_path",historyPath);
        outbounds.put(group);
        for(int i=0;i<configs.size();i++){
            JSONObject out=new JSONObject(configs.get(i).getJSONArray("outbounds").getJSONObject(0).toString());
            if(!out.optString("tag").equals("proxy")||!out.has("server")||out.has("detour"))throw new java.io.IOException("Unsupported Auto server config");
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder key=new StringBuilder("yay-node-");for(byte b:hash)key.append(String.format(java.util.Locale.ROOT,"%02x",b&255));String tag=key.toString();if(!seen.add(tag))continue;out.put("tag",tag);members.put(tag);outbounds.put(out);
        }
        config.put("outbounds",outbounds);return config;
    }
}
