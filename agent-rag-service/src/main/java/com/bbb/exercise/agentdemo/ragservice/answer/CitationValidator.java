package com.bbb.exercise.agentdemo.ragservice.answer;
import java.util.*;
import java.util.regex.Pattern;
public final class CitationValidator {
    private CitationValidator(){}
    private static final Pattern CITATION=Pattern.compile("\\[S([^\\]]*)\\]");
    public static Set<Integer> validate(String answer,int evidenceCount){
        if(answer==null||answer.isBlank())throw new IllegalArgumentException("EMPTY_ANSWER");
        var citations=new LinkedHashSet<Integer>();var match=CITATION.matcher(answer);
        while(match.find()){String id=match.group(1);if(!id.matches("[1-9][0-9]*"))throw new IllegalArgumentException("CITATION_INVALID");int index;try{index=Integer.parseInt(id);}catch(NumberFormatException e){throw new IllegalArgumentException("CITATION_INVALID");}if(index>evidenceCount)throw new IllegalArgumentException("CITATION_INVALID");citations.add(index);}
        if(citations.isEmpty())throw new IllegalArgumentException("CITATION_MISSING");
        for(String paragraph:answer.split("\\n\\s*\\n")){if(!paragraph.isBlank()&&paragraph.replaceAll("[#*\\s]","").length()>20&&!CITATION.matcher(paragraph).find())throw new IllegalArgumentException("UNCITED_CLAIM");}
        return citations;
    }
}
