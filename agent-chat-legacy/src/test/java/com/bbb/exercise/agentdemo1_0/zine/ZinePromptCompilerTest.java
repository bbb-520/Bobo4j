package com.bbb.exercise.agentdemo1_0.zine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ZinePromptCompilerTest {

    private final ZinePromptCompiler compiler = new ZinePromptCompiler();

    @Test
    void gatheredModeKeepsPhotoAnchorAndTornPaperRules() {
        ZinePromptCompiler.CompiledPrompt result = compiler.compile(
                ZineMode.GATHERED, ZineLanguage.CHINESE, "窗边", "保留人物与窗的关系");

        assertThat(result.prompt()).contains("25–45%", "hand-torn fibrous edge", "窗边");
        assertThat(result.prompt()).doesNotContain("original illustration, paper, and typography only");
        assertThat(result.rationale()).contains("真实现场锚点");
    }

    @Test
    void distillationModeExplicitlyRemovesPhotographicPixels() {
        ZinePromptCompiler.CompiledPrompt result = compiler.compile(
                ZineMode.DISTILLATION, ZineLanguage.BILINGUAL, null, "单色块模式");

        assertThat(result.prompt()).contains("单色块模式", "Do not reproduce, embed, crop, collage, trace");
        assertThat(result.prompt()).contains("original illustration, paper, and");
        assertThat(result.prompt()).contains("Chinese–English");
    }
}
