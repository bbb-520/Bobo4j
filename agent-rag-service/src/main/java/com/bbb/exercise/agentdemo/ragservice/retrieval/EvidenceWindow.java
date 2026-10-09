package com.bbb.exercise.agentdemo.ragservice.retrieval;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded verbatim excerpts; byte limits also bound multilingual model input conservatively. */
public final class EvidenceWindow {
    private EvidenceWindow() {}
    public static String select(String text,String query,int maximumBytes) {
        if(maximumBytes<32)throw new IllegalArgumentException("Window too small");
        if(text.getBytes(StandardCharsets.UTF_8).length<=maximumBytes)return text;
        int[] chars=new int[text.codePointCount(0,text.length())+1],bytes=new int[chars.length];
        for(int i=0;i<chars.length-1;i++){
            int cp=text.codePointAt(chars[i]);chars[i+1]=chars[i]+Character.charCount(cp);
            bytes[i+1]=bytes[i]+(cp<=127?1:cp<=2047?2:cp<=65535?3:4);
        }
        String lower=text.toLowerCase(Locale.ROOT);var terms=new LinkedHashMap<String,Integer>();
        var identifiers=Pattern.compile("[a-z0-9][a-z0-9_.-]{1,63}").matcher(query.toLowerCase(Locale.ROOT));
        while(identifiers.find()&&terms.size()<32)terms.put(identifiers.group(),8);
        var chinese=Pattern.compile("[\\p{IsHan}]+").matcher(query);
        while(chinese.find()&&terms.size()<64){String value=chinese.group();for(int i=0;i<value.length()-1&&terms.size()<64;i++)terms.putIfAbsent(value.substring(i,i+2),1);}
        var starts=new TreeSet<Integer>();starts.add(0);
        for(String term:terms.keySet()){
            int cursor=0,hits=0;
            while(hits++<32&&(cursor=lower.indexOf(term,cursor))>=0){
                int cp=Arrays.binarySearch(chars,cursor);if(cp<0)cp=-cp-2;
                starts.add(Math.max(0,cp-maximumBytes/8));cursor+=term.length();
            }
        }
        String best="";int highest=0;
        for(int start:starts){
            int end=Arrays.binarySearch(bytes,bytes[start]+maximumBytes);if(end<0)end=-end-2;end=Math.min(end,chars.length-1);
            String candidate=text.substring(chars[start],chars[end]);String normalized=candidate.toLowerCase(Locale.ROOT);int score=0;
            for(var term:terms.entrySet())if(normalized.contains(term.getKey()))score+=term.getValue();
            if(score>highest){highest=score;best=candidate;}
        }
        if(highest>0)return best;
        // Semantic matches can lack lexical overlap. Preserve both ends rather than always losing the tail.
        int half=(maximumBytes-5)/2;
        int head=Arrays.binarySearch(bytes,half);if(head<0)head=-head-2;
        int tail=Arrays.binarySearch(bytes,bytes[bytes.length-1]-half);if(tail<0)tail=-tail-1;
        return text.substring(0,chars[head])+"\n…\n"+text.substring(chars[tail]);
    }
}
