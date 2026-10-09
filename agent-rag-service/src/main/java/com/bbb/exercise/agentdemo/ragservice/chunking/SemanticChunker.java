package com.bbb.exercise.agentdemo.ragservice.chunking;
import com.bbb.exercise.agentdemo.ragservice.domain.RagData.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
public final class SemanticChunker {
    public static final String VERSION="semantic-window-v1";
    public List<Chunk> chunk(String document,int version,List<Block> blocks,List<List<Float>> vectors,int maxCharacters) {
        if(blocks.size()!=vectors.size()||maxCharacters<64) throw new IllegalArgumentException("Invalid semantic inputs");
        List<Double> similarities=new ArrayList<>();for(int i=1;i<blocks.size();i++)similarities.add(cosine(vectors.get(i-1),vectors.get(i)));
        return chunkWithSimilarities(document,version,blocks,similarities,maxCharacters);
    }
    public List<Chunk> chunkWithSimilarities(String document,int version,List<Block> blocks,List<Double> boundaries,int maxCharacters) {
        if(boundaries.size()!=Math.max(0,blocks.size()-1)||maxCharacters<64)throw new IllegalArgumentException("Semantic boundary shape");
        var units=new ArrayList<Unit>();
        for(int i=0;i<blocks.size();i++) split(blocks.get(i),List.of((float)i),maxCharacters,units);
        if(units.isEmpty()) return List.of();
        var similarities=new ArrayList<Double>();
        for(int i=1;i<units.size();i++){int before=units.get(i-1).vector.getFirst().intValue(),after=units.get(i).vector.getFirst().intValue();similarities.add(before==after?1:boundaries.get(after-1));}
        var sorted=new ArrayList<>(similarities); sorted.sort(Double::compare);
        double percentile=sorted.isEmpty()?0:sorted.get((int)((sorted.size()-1)*.2));
        var result=new ArrayList<Chunk>(); var current=new ArrayList<Unit>(); int size=0;
        for(int i=0;i<units.size();i++) {
            Unit unit=units.get(i); boolean topic=false;
            if(i>0) {
                double similarity=similarities.get(i-1);
                double baseline=0;int count=0;
                for(int j=Math.max(0,i-4);j<i-1;j++){baseline+=similarities.get(j);count++;}
                baseline=count==0?1:baseline/count;
                topic=similarity<=percentile && similarity<.55 && baseline-similarity>=.15;
            }
            int length=unit.text.codePointCount(0,unit.text.length());
            if(!current.isEmpty() && (topic || !current.getLast().block.title().equals(unit.block.title()) || size+length+1>maxCharacters)) {
                emit(document,version,current,result); current=new ArrayList<>();size=0;
            }
            current.add(unit);size+=length+1;
        }
        if(!current.isEmpty()) emit(document,version,current,result);
        return List.copyOf(result);
    }
    private static void split(Block block,List<Float> vector,int maximum,List<Unit> out) {
        String text=block.text(); String header=block.kind().equals("TABLE")&&text.contains("\n")?text.substring(0,text.indexOf('\n')):"";
        // A repeated header is context. Its original full text is still split as body on the first pass.
        int headerLimit=Math.min(256,maximum/4);
        if(header.codePointCount(0,header.length())>headerLimit)header=header.substring(0,header.offsetByCodePoints(0,headerLimit));
        int start=0;
        while(start<text.length()) {
            int reserve=start>0&&!header.isEmpty()?header.codePointCount(0,header.length())+1:0;
            int end=text.offsetByCodePoints(start,Math.min(maximum-reserve,text.codePointCount(start,text.length())));
            if(end<text.length()) {
                int candidate=Math.max(text.lastIndexOf('\n',end-1),Math.max(text.lastIndexOf('。',end-1),text.lastIndexOf(' ',end-1)));
                if(candidate>start+(end-start)/2) end=candidate+1;
            }
            String piece=text.substring(start,end);
            if(start>0&&!header.isEmpty()) piece=header+"\n"+piece;
            if(!piece.isBlank()) out.add(new Unit(block,piece,block.charStart()+start,block.charStart()+end,vector));
            start=end;
        }
    }
    private static void emit(String document,int version,List<Unit> units,List<Chunk> out) {
        String text=String.join("\n",units.stream().map(u->u.text).toList()); Unit first=units.getFirst();Unit last=units.getLast();
        String hash=hash(text);String id=hash(document+":"+version+":"+first.start+":"+last.end+":"+hash);
        out.add(new Chunk(id,document,version,text,first.block.title(),first.block.page(),first.block.ordinal(),first.start,last.end,hash));
    }
    public static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public static double cosine(List<Float> a,List<Float> b) {
        if(a.size()!=b.size()||a.isEmpty()) throw new IllegalArgumentException("Vector shape");
        double dot=0,x=0,y=0;for(int i=0;i<a.size();i++){if(!Float.isFinite(a.get(i))||!Float.isFinite(b.get(i)))throw new IllegalArgumentException("Vector finite");dot+=a.get(i)*b.get(i);x+=a.get(i)*a.get(i);y+=b.get(i)*b.get(i);}
        return x==0||y==0?0:dot/Math.sqrt(x*y);
    }
    private record Unit(Block block,String text,int start,int end,List<Float> vector) {}
}
