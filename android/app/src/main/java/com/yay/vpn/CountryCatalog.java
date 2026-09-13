package com.yay.vpn;

import org.json.*;
import java.util.*;

/** Country labels are provider metadata, not IP geolocation. No endpoint is shown in UI. */
final class CountryCatalog {
    static final class Country {
        final String code, fallback;
        final List<JSONObject> nodes=new ArrayList<>();
        Country(String code,String fallback){this.code=code;this.fallback=fallback;}
        String label(String language){return code.isEmpty()?fallback:new Locale("",code).getDisplayCountry(Locale.forLanguageTag(language));}
        String flag(){if(code.length()!=2)return "◎";return new String(Character.toChars(0x1f1e6+code.charAt(0)-'A'))+new String(Character.toChars(0x1f1e6+code.charAt(1)-'A'));}
    }
    static List<Country> group(JSONArray servers){
        Map<String,Country> grouped=new LinkedHashMap<>();
        for(int i=0;i<servers.length();i++){
            JSONObject s=servers.optJSONObject(i);if(s==null||s.optInt("enabled",1)!=1)continue;
            String location=s.optString("location").trim(),code=code(location);
            if(code.isEmpty())code=code(s.optString("name"));
            String key=code.isEmpty()?"other:"+location:code;
            Country c=grouped.get(key);if(c==null){c=new Country(code,location.isEmpty()?"—":location);grouped.put(key,c);}c.nodes.add(s);
        }
        List<Country> result=new ArrayList<>(grouped.values());result.sort(Comparator.comparing(c->c.label("en")));return result;
    }
    static String code(String raw){
        String s=raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        String[][] names={{"SG","singapore"},{"HK","hongkong"},{"JP","japan"},{"IN","india"},{"ZA","southafrica"},{"KR","southkorea"},{"US","unitedstates"},{"GB","unitedkingdom"},{"AU","australia"},{"DE","germany"},{"ES","spain"},{"BR","brazil"},{"ID","indonesia"},{"CN","china"},{"NL","netherlands"},{"FR","france"},{"CA","canada"},{"TW","taiwan"},{"TH","thailand"},{"MY","malaysia"}};
        for(String[] n:names)if(s.equals(n[0].toLowerCase(Locale.ROOT))||s.contains(n[1]))return n[0];return "";
    }
}
