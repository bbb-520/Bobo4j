package com.bbb.exercise.agentdemo.ragservice.parsing;

import com.bbb.exercise.agentdemo.ragservice.domain.RagData.*;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** One document per bounded subprocess. No network parser, embedded attachments or OCR. */
public final class TikaParserMain {
    public static void main(String[] args) {
        try {
            System.setProperty("javax.xml.accessExternalDTD","");System.setProperty("javax.xml.accessExternalSchema","");System.setProperty("javax.xml.accessExternalStylesheet","");
            Path source=Path.of(args[0]),output=Path.of(args[1]);int maximum=Integer.parseInt(args[2]);
            if(Files.size(source)>100L*1024*1024){System.exit(12);return;}
            var context=new ParseContext();context.set(EmbeddedDocumentExtractor.class,new EmbeddedDocumentExtractor(){
                public boolean shouldParseEmbedded(Metadata m){return false;}
                public void parseEmbedded(InputStream stream,ContentHandler handler,Metadata m,boolean html){}
            });
            var pdf=new PDFParserConfig();pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);context.set(PDFParserConfig.class,pdf);
            var metadata=new Metadata();var handler=new Blocks(maximum);
            try(var in=Files.newInputStream(source)){new AutoDetectParser().parse(in,handler,metadata,context);}
            handler.flush();if(handler.blocks.isEmpty()){System.exit(11);return;}
            ParsedFile.write(output,new Parsed(List.copyOf(handler.blocks),metadata.get("Content-Type"),handler.codepoints));
        }catch(Throwable error){boolean limit=false,memory=false;for(Throwable cause=error;cause!=null;cause=cause.getCause()){if(String.valueOf(cause.getMessage()).contains("PARSE_OUTPUT_LIMIT"))limit=true;if(cause instanceof OutOfMemoryError)memory=true;}System.exit(memory?13:limit?12:10);}
    }
    static final class Blocks extends DefaultHandler {
        final int maximum;final List<Block> blocks=new ArrayList<>();final StringBuilder text=new StringBuilder();
        final TreeMap<Integer,String> headings=new TreeMap<>();int codepoints,offset,page,heading,skip;String kind="PARAGRAPH",tableHeader="";boolean row,body,priorHigh;
        Blocks(int maximum){this.maximum=maximum;}
        @Override public void startElement(String uri,String local,String qName,Attributes a)throws SAXException{
            String tag=local.isEmpty()?qName:local;
            if(tag.equals("body"))body=true;
            if(tag.equals("script")||tag.equals("style")){skip++;return;}
            if(tag.equals("div")&&"page".equals(a.getValue("class"))){flush();page++;}
            if(tag.matches("h[1-6]")){flush();heading=Integer.parseInt(tag.substring(1));kind="HEADING";}
            if(tag.equals("p")||tag.equals("li")){flush();kind="PARAGRAPH";}
            if(tag.equals("table")){flush();tableHeader="";}
            if(tag.equals("tr")){flush();kind="TABLE";row=true;}
            if((tag.equals("td")||tag.equals("th"))&&text.length()>0)append(" | ");
        }
        @Override public void characters(char[] ch,int start,int length)throws SAXException{if(body&&skip==0)append(new String(ch,start,length));}
        void append(String value)throws SAXException{for(int i=0;i<value.length();i++){char c=value.charAt(i);if(!(priorHigh&&Character.isLowSurrogate(c)))codepoints++;priorHigh=Character.isHighSurrogate(c);}if(codepoints>maximum)throw new SAXException("PARSE_OUTPUT_LIMIT");text.append(value);}
        @Override public void endElement(String uri,String local,String qName)throws SAXException{
            String tag=local.isEmpty()?qName:local;if(tag.equals("script")||tag.equals("style")){skip=Math.max(0,skip-1);return;}
            if(tag.matches("h[1-6]")||tag.equals("p")||tag.equals("li")||tag.equals("tr")){flush();row=false;}
        }
        void flush(){String value=text.toString().strip();text.setLength(0);if(value.isBlank())return;
            if(blocks.size()>=100000||value.length()>maximum*2L)throw new IllegalStateException("PARSE_OUTPUT_LIMIT");
            if(heading>0){if(value.length()>4096)throw new IllegalStateException("PARSE_OUTPUT_LIMIT");headings.tailMap(heading,true).clear();headings.put(heading,value);heading=0;}
            String title=String.join(" / ",headings.values());if(title.isBlank())title="正文";
            if(title.length()>8192)throw new IllegalStateException("PARSE_OUTPUT_LIMIT");
            if(kind.equals("TABLE")){if(tableHeader.isEmpty())tableHeader=value;else value=tableHeader+"\n"+value;}
            int end=offset+value.length();blocks.add(new Block("b"+blocks.size(),kind,title,value,blocks.size(),offset,end,page==0?null:page));offset=end+1;
        }
    }
}
