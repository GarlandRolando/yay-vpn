package com.yay.vpn;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class SelectionPolicyTest {
    @Test public void unreachableServersAreNeverSelected(){
        Map<String,Long> m=new HashMap<>();m.put("timeout",-1L);m.put("slow",500L);m.put("fast",50L);
        assertEquals(Arrays.asList("fast","slow"),SelectionPolicy.rank(m,new Random(3)));
    }
    @Test public void randomSelectionStaysWithinFastTieBand(){
        Map<String,Long> m=new LinkedHashMap<>();m.put("best",100L);m.put("close",108L);m.put("outside",111L);m.put("slow",500L);
        Set<String> chosen=new HashSet<>();
        for(int i=0;i<100;i++){List<String> ids=SelectionPolicy.rank(m,new Random(i*123L));chosen.add(ids.get(0));assertEquals("outside",ids.get(2));assertEquals("slow",ids.get(3));}
        assertEquals(new HashSet<>(Arrays.asList("best","close")),chosen);
    }
    @Test public void noSuccessfulMeasurementsMeansNoFallbackToUntestedServers(){
        assertTrue(SelectionPolicy.rank(Collections.singletonMap("failed",-1L),new Random(1)).isEmpty());
        assertTrue(SelectionPolicy.rank(Collections.emptyMap(),new Random(1)).isEmpty());
    }
}
