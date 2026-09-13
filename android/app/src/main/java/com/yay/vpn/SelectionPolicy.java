package com.yay.vpn;
import java.util.*;

/** Keep fast servers first and spread clients only among close latency ties. */
final class SelectionPolicy {
    static List<String> rank(Map<String,Long> measurements,Random random){
        List<String> live=new ArrayList<>();
        for(Map.Entry<String,Long> e:measurements.entrySet())if(e.getValue()>=0)live.add(e.getKey());
        live.sort(Comparator.comparingLong(measurements::get));
        if(live.isEmpty())return live;
        long best=measurements.get(live.get(0)),tolerance=Math.max(10,best/20);int ties=0;
        while(ties<live.size()&&measurements.get(live.get(ties))<=best+tolerance)ties++;
        Collections.shuffle(live.subList(0,ties),random);return live;
    }
}
