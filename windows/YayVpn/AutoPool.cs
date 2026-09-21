using System.IO;
using System.Text.Json.Nodes;
namespace YayVpn;
// Shared Yay Auto native monitor; fresh cloud grants remain mandatory.
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
    internal static List<string> Prefer(IEnumerable<string> order,IEnumerable<string> previous){
        var all=order.Distinct().ToList();var allowed=all.ToHashSet();return previous.Where(allowed.Contains).Concat(all).Distinct().ToList();
    }
    internal static JsonObject Build(IReadOnlyList<JsonObject> configs,string historyPath=""){
        if(configs.Count==0||configs.Count>Size)throw new IOException("No authorized Auto servers available");
        var config=configs[0].DeepClone().AsObject();var members=new JsonArray();var outbounds=new JsonArray();var seen=new HashSet<string>();
        for(int i=0;i<configs.Count;i++){
            var outbound=configs[i]["outbounds"]?[0]?.DeepClone().AsObject()??throw new IOException("Invalid Auto server");
            if(outbound["tag"]?.GetValue<string>()!="proxy"||outbound["server"]==null||outbound["detour"]!=null)throw new IOException("Unsupported Auto server config");
            string tag="yay-node-"+Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(outbound.ToJsonString()))).ToLowerInvariant();if(!seen.Add(tag))continue;outbound["tag"]=tag;members.Add(tag);outbounds.Add(outbound);
        }
        outbounds.Insert(0,new JsonObject{["type"]="yay-auto",["tag"]="proxy",["outbounds"]=members,
            ["test_urls"]=new JsonArray("https://cp.cloudflare.com/generate_204","https://www.gstatic.com/generate_204"),["history_path"]=historyPath});
        config["outbounds"]=outbounds;return config;
    }
}
