namespace YayVpn;
static class ConnectionPolicy {
    internal static List<string> Lightning(IReadOnlyList<string> candidates,Random random)=>
        candidates.Count==0?new():new(){candidates[random.Next(candidates.Count)]};
    internal static List<string> Enhanced(IReadOnlyDictionary<string,long> pings,Random random){
        var live=pings.Where(p=>p.Value>=0).OrderBy(p=>p.Value).ToList();if(live.Count==0)return new();
        long best=live[0].Value,tolerance=Math.Max(10,best/20);
        var fast=live.Where(p=>p.Value<=best+tolerance).Select(p=>p.Key).ToList();
        random.Shuffle(System.Runtime.InteropServices.CollectionsMarshal.AsSpan(fast));
        return fast.Concat(live.Select(p=>p.Key).Except(fast)).Take(3).ToList();
    }
}
