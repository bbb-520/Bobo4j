package com.bbb.exercise.agentdemo.ragservice;
import com.bbb.exercise.agentdemo.ragservice.chunking.SemanticChunker;
import com.bbb.exercise.agentdemo.ragservice.domain.RagData.Block;
import java.util.List;
/** Dependency-free RED/GREEN smoke entry point while parser dependencies download. */
public class SemanticRedCheck {
    public static void main(String[] args) {
        var blocks=List.of(new Block("a","PARAGRAPH","topic","first.",0,0,6,null),new Block("b","PARAGRAPH","topic","second.",1,7,14,null));
        var chunks=new SemanticChunker().chunk("d",1,blocks,List.of(List.of(1f,0f),List.of(1f,0f)),2048);
        if(chunks.size()!=1) throw new AssertionError("Uniform topic must stay together");
    }
}
