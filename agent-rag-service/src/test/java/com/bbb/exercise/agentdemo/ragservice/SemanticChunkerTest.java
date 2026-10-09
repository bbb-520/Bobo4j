package com.bbb.exercise.agentdemo.ragservice;
import com.bbb.exercise.agentdemo.ragservice.chunking.SemanticChunker;
import com.bbb.exercise.agentdemo.ragservice.domain.RagData.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class SemanticChunkerTest {
    @Test void wideTableHeaderAlwaysAdvancesAndPreservesEveryOriginalCharacter() {
        String text="超宽列名".repeat(700)+"\n"+"表格正文🙂".repeat(600)+"TAIL_97";
        var block=new Block("wide","TABLE","wide",text,0,20,20+text.length(),null);
        var chunks=new SemanticChunker().chunk("d",1,List.of(block),List.of(List.of(1f)),2048);
        assertThat(chunks).hasSizeGreaterThan(2).allSatisfy(c->{
            assertThat(c.text().codePointCount(0,c.text().length())).isLessThanOrEqualTo(2048);
            assertThat(c.charEnd()).isGreaterThan(c.charStart());
        });
        int cursor=20;
        for(var chunk:chunks){assertThat(chunk.charStart()).isEqualTo(cursor);cursor=chunk.charEnd();}
        assertThat(cursor).isEqualTo(20+text.length());
        assertThat(chunks.getLast().text()).contains("TAIL_97");
    }
    @Test void uniformSimilarityDoesNotForceLowestPercentileBoundaries() {
        List<Block> b=new ArrayList<>(); List<List<Float>> v=new ArrayList<>();
        for(int i=0;i<20;i++){b.add(new Block("b"+i,"PARAGRAPH","same","sentence "+i+".",i,i*20,i*20+12,null));v.add(List.of(1f,0f));}
        assertThat(new SemanticChunker().chunk("d",1,b,v,2048)).hasSize(1);
    }
    @Test void strongTopicDropAndHeadingCauseRealBoundaries() {
        var blocks=List.of(new Block("a","PARAGRAPH","A","alpha topic",0,0,11,1),new Block("b","PARAGRAPH","A","beta topic",1,12,22,2),new Block("c","PARAGRAPH","A","gamma topic",2,23,34,3));
        assertThat(new SemanticChunker().chunk("d",1,blocks,List.of(List.of(1f,0f),List.of(1f,0f),List.of(0f,1f)),2048)).hasSize(2);
    }
    @Test void longTailAndTableHeadersRemainLocatableWithoutInventedPages() {
        var b=new Block("a","TABLE","Budget","Item | Value\n"+"尾部数值 97。".repeat(800),7,44,6444,null);
        var chunks=new SemanticChunker().chunk("d",3,List.of(b),List.of(List.of(1f)),2048);
        assertThat(chunks).hasSizeGreaterThan(2).allSatisfy(c->{assertThat(c.text().codePointCount(0,c.text().length())).isLessThanOrEqualTo(2048);assertThat(c.page()).isNull();assertThat(c.ordinal()).isEqualTo(7);assertThat(c.charStart()).isGreaterThanOrEqualTo(44);assertThat(c.text()).startsWith("Item | Value");});
        assertThat(chunks.getLast().text()).contains("97");
    }
}
