package de.evp.helfer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Same pinned, ASCII PSL rules as Kadri; lazy and dependency-free. */
final class PublicSuffix {
    private PublicSuffix() {}
    private static final String EXPECTED = "0c203a51804ede904082d7f106322de9ec635431d3c0d982b3dea3c842106436";
    private static class Rules { static final String[] ALL=load(); }
    private static String[] load() {
        try(InputStream in=PublicSuffix.class.getResourceAsStream("public_suffix_rules.txt")) {
            if(in==null) return null;
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
            while((n=in.read(b))!=-1){if(out.size()+n>512*1024)return null;out.write(b,0,n);}
            byte[] bytes=out.toByteArray();
            StringBuilder hex=new StringBuilder();
            for(byte x:MessageDigest.getInstance("SHA-256").digest(bytes))hex.append(String.format(Locale.ROOT,"%02x",x&255));
            if(!EXPECTED.contentEquals(hex))return null;
            String[] rules=new String(bytes,StandardCharsets.US_ASCII).split(",");
            Arrays.sort(rules);return rules;
        } catch(Exception e){return null;}
    }
    static boolean ready(){return Rules.ALL!=null;}
    static String site(String host) {
        if(host==null)return "";
        String h=host.toLowerCase(Locale.ROOT);
        while(h.endsWith("."))h=h.substring(0,h.length()-1);
        // Browser URLs are serialized ASCII/punycode. Do not apply Java's older
        // IDNA mapping (e.g. sharp-s -> ss) to credential boundaries.
        for(int i=0;i<h.length();i++)if(h.charAt(i)>127)return "";
        if(h.isEmpty()||h.matches("[0-9.]+")||h.indexOf(':')>=0)return h;
        String[] rules=Rules.ALL;
        if(rules==null)return h; // fail closed to exact-host credentials if packaged data is damaged
        String[] p=h.split("\\.");int suffix=1;
        for(int i=0;i<p.length;i++){
            String rest=String.join(".",Arrays.copyOfRange(p,i,p.length));
            if(Arrays.binarySearch(rules,"!"+rest)>=0){suffix=p.length-i-1;break;}
            if(Arrays.binarySearch(rules,rest)>=0){suffix=p.length-i;break;}
            if(i+1<p.length&&Arrays.binarySearch(rules,"*."+String.join(".",Arrays.copyOfRange(p,i+1,p.length)))>=0){suffix=p.length-i;break;}
        }
        return suffix>=p.length?h:String.join(".",Arrays.copyOfRange(p,p.length-suffix-1,p.length));
    }
}
