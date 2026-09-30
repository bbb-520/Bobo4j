package com.bbb.exercise.agentdemo1_0.generation;

import com.bbb.exercise.agentdemo1_0.conversation.ConversationIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GenerationOrchestratorTest {

    @Test
    void createsBoundedGenerateCommand() {
        GenerationCommand command = GenerationOrchestrator.command(
                ConversationIntent.GENERATE, "做成纸刊", "asset-1", null, 10);

        assertEquals(GenerationCommand.Mode.GENERATE, command.mode());
        assertEquals("asset-1", command.sourceAssetId());
        assertEquals(4, command.variantCount());
        assertNull(command.parentVariantId());
    }

    @Test
    void remixRequiresParentVariantAndKeepsInstruction() {
        GenerationCommand command = GenerationOrchestrator.command(
                ConversationIntent.REMIX, "保留人物，换成蓝色纸张", "asset-1", "variant-7", 2);

        assertEquals(GenerationCommand.Mode.REMIX, command.mode());
        assertEquals("variant-7", command.parentVariantId());
        assertEquals("保留人物，换成蓝色纸张", command.prompt());
    }

    @Test
    void sceneCardTrimsAndDefensivelyCopiesLists() {
        SceneCard card = new SceneCard("  海边  ", List.of(" 人物 "), " 蓝灰 ", " 中景 ", " 安静 ", "asset-1");

        assertEquals("海边", card.summary());
        assertEquals(List.of("人物"), card.subjects());
        assertThrows(UnsupportedOperationException.class, () -> card.subjects().add("x"));
    }
}
