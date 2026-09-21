using System.IO;
using System.Text.Json.Nodes;
namespace YayVpn;
// Use upstream sing-box URLTest, not Hiddify's fork-only "balancer" outbound.
static class AutoPool {
    internal const int Size=12;
    internal static List<string> Order(IEnumerable<(string Id,string Country)> nodes,Random random){
        var groups=nodes.Where(n=>!string.IsNullOrWhiteSpace(n.Id)).DistinctBy(n=>n.Id)
            .GroupBy(n=>n.Country).Select(g=>g.Select(n=>n.Id).ToList()).ToList();
        foreach(var group in groups)random.Shuffle(System.Runtime.InteropServices.CollectionsMarshal.AsSpan(group));
        random.Shuffle(System.Runtime.InteropServices.CollectionsMarshal.AsSpan(groups));
        var result=new List<string>();
        for(int i=0;groups.Any(g=>g.Count>i);i++)foreach(var group in groups)if(i<group.Count)result.Add(group[i]);
        return result;
    }
    internal static JsonObject Build(IReadOnlyList<JsonObject> configs){
        if(configs.Count==0||configs.Count>Size)throw new IOException("No authorized Auto servers available");
        var config=configs[0].DeepClone().AsObject();var members=new JsonArray();var outbounds=new JsonArray();
        for(int i=0;i<configs.Count;i++){
            var outbound=configs[i]["outbounds"]?[0]?.DeepClone().AsObject()??throw new IOException("Invalid Auto server");
            if(outbound["tag"]?.GetValue<string>()!="proxy"||outbound["server"]==null||outbound["detour"]!=null)throw new IOException("Unsupported Auto server config");
            string tag="yay-node-"+i;outbound["tag"]=tag;members.Add(tag);outbounds.Add(outbound);
        }
        outbounds.Insert(0,new JsonObject{["type"]="urltest",["tag"]="proxy",["outbounds"]=members,
            ["url"]="https://cp.cloudflare.com/generate_204",["interval"]="30s",["tolerance"]=100,
            ["idle_timeout"]="30m",["interrupt_exist_connections"]=false});
        config["outbounds"]=outbounds;return config;
    }
}
