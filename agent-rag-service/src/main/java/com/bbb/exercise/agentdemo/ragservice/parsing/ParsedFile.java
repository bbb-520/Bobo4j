package com.bbb.exercise.agentdemo.ragservice.parsing;
import com.bbb.exercise.agentdemo.ragservice.domain.RagData.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
public final class ParsedFile {
    private ParsedFile() {}
    public static void write(Path path,Parsed parsed) throws IOException {
        try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            out.writeInt(0x52414731);string(out,parsed.mediaType());out.writeInt(parsed.characters());out.writeInt(parsed.blocks().size());
            for(Block b:parsed.blocks()){string(out,b.blockId());string(out,b.kind());string(out,b.title());string(out,b.text());out.writeInt(b.ordinal());out.writeInt(b.charStart());out.writeInt(b.charEnd());out.writeInt(b.page()==null?-1:b.page());}
        }
    }
    public static Parsed read(Path path,int maximum) throws IOException {
        if(Files.size(path)>maximum*8L+1_000_000)throw new IOException("PARSE_OUTPUT_LIMIT");
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))){
            if(in.readInt()!=0x52414731)throw new IOException("Invalid parse result");String mime=string(in,512);int chars=in.readInt(),count=in.readInt();
            if(chars<0||chars>maximum||count<0||count>maximum)throw new IOException("PARSE_OUTPUT_LIMIT");
            var blocks=new ArrayList<Block>();long total=0;
            for(int i=0;i<count;i++){String id=string(in,128),kind=string(in,128),title=string(in,8192),text=string(in,maximum*4);total+=text.codePointCount(0,text.length());if(total>maximum)throw new IOException("PARSE_OUTPUT_LIMIT");int ordinal=in.readInt(),start=in.readInt(),end=in.readInt(),page=in.readInt();blocks.add(new Block(id,kind,title,text,ordinal,start,end,page<0?null:page));}
            return new Parsed(List.copyOf(blocks),mime,chars);
        }
    }
    private static void string(DataOutputStream out,String value)throws IOException{byte[] b=value.getBytes(StandardCharsets.UTF_8);out.writeInt(b.length);out.write(b);}
    private static String string(DataInputStream in,int max)throws IOException{int size=in.readInt();if(size<0||size>max)throw new IOException("PARSE_OUTPUT_LIMIT");byte[] b=in.readNBytes(size);if(b.length!=size)throw new EOFException();return new String(b,StandardCharsets.UTF_8);}
}
