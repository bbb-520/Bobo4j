package com.bbb.exercise.agentdemo1_0.zine;

import org.springframework.stereotype.Component;

/**
 * Compiles the two public creative paths into a provider-ready prompt.
 *
 * <p>The image model performs the actual Scene Card reading because it is the
 * component that can see the uploaded image. This compiler keeps the rules
 * deterministic, inspectable, and independent from a particular provider.
 */
@Component
public class ZinePromptCompiler {

    public CompiledPrompt compile(ZineMode mode, ZineLanguage language, String text, String guidance) {
        String suppliedText = clean(text);
        String userGuidance = clean(guidance);
        String textInstruction = textInstruction(language, suppliedText);
        String guidanceInstruction = userGuidance.isEmpty()
                ? "No additional user art direction was supplied; make source-aware decisions yourself."
                : "Additional user art direction, to be treated as a creative brief: " + userGuidance;

        if (mode == ZineMode.DISTILLATION) {
            return new CompiledPrompt(distillationPrompt(textInstruction, guidanceInstruction),
                    "将原图仅作为语义与情绪来源，提取一个核心隐喻，再用纸张、插画、留白和单一高纯度色彩重新组织成独立纸刊作品。原始照片不会进入成品。 ");
        }
        return new CompiledPrompt(gatheredPrompt(textInstruction, guidanceInstruction),
                "保留照片作为真实现场锚点，用源图中的轮廓或空间关系延展出大面积克制的纸上插画，并以一处源自场景的高纯度色彩串联照片、撕纸边界与留白。 ");
    }

    private static String gatheredPrompt(String textInstruction, String guidanceInstruction) {
        return """
                Create a calm, tactile Gathered Scenes Zine poster from the supplied reference image.
                First inspect the image and build an internal Scene Card: identify the 1–2 core subjects,
                supporting atmosphere, spatial invariants, dominant gesture, visual-weight map, native palette,
                one or two source-derived shapes, natural quiet areas, and the smallest semantic minimum that
                keeps this particular scene recognizable. Preserve relationships before details.

                Canvas and hierarchy: use a vertical 3:5 portrait composition, a flat scanned warm-cream paper
                surface, diffuse light, and no mockup framing. Keep roughly 25–45%% of the scene photographic as
                one strong factual anchor. Let a large but sparse source-derived illustration field influence
                roughly 45–70%% of the poster while keeping most of that field quiet and breathable. Choose one
                primary grammar only—silhouette, contour, field, rhythm, or cut-paper—and at most one supporting
                grammar. Remove 60–80%% of small descriptive detail; merge foliage, branches, crowds, texture,
                and repeated forms into a few large legible masses and directional gestures.

                Material transition and color: create a visibly irregular hand-torn fibrous edge where the photo
                meets paper or illustration. Keep the tear narrow, asymmetrical, flat-scanned, and tactile; do not
                use a clean digital mask, sticker border, heavy shadow, curled paper, or uniform deckled frame.
                Select exactly one high-chroma added hue based on the source palette. Derive its contour, position,
                or rhythm from the source and make it perform compositional work as a bridge, counterweight,
                focal reinforcement, or eye-path direction. It must touch, overlap, or cross the photo–paper
                boundary; never use a detached decorative swatch.

                %s
                %s
                Reproduction mood: tactile paper fibers, restrained grain, dry ink or halftone behavior, slight
                scan noise, imperfect print coverage, active negative space, and an editorial quietness. Add no
                logos, CTA, watermark, glossy 3D, cinematic lighting, anime, cute cartoon, dense scrapbooking,
                multiple competing illustration styles, extra bright hues, typography, labels, letters, numbers,
                logos, watermarks, buttons, or any other written copy.
                """.formatted(textInstruction, guidanceInstruction);
    }

    private static String distillationPrompt(String textInstruction, String guidanceInstruction) {
        return """
                Create an independently compelling Scene Distillation Zine poster from the supplied reference image.
                Inspect the image first and build an internal Distillation Card: semantic nucleus, one core subject,
                one to three supporting cues, dominant gesture, one meaningful spatial relationship, visual-weight
                map, native palette, material or environmental behavior, emotional residue, discard list, and two to four
                source anchors. Then write one specific expressive proposition, one central tension, and one
                source-derived visual metaphor. Recompose freely; the final artwork must stand on its own without
                the source photo.

                The final canvas is a flat paper poster. Use portrait 3:5 unless the source is clearly landscape,
                in which case use landscape 5:3. Keep 68–85%% quiet paper, one active illustration cluster around
                12–32%% of the canvas, one dominant mass, one to three supporting forms, and one restrained texture
                field. Use editorial abstraction: remove 65–90%% of descriptive detail and choose one primary
                grammar—cut-paper mass, dry-print silhouette, broken contour, rhythm field, fragment stack, or
                orbit/drift—with at most one supporting grammar. Let scale, interval, direction, enclosure,
                interruption, and material embody the proposition rather than adding decorative motifs.

                Choose one primary edge treatment that serves the idea: torn fiber, layered grayscale, stippled
                dissolution, irregular source-derived marks, or a natural isolated contour. Choose Standard Accent
                Mode by default: one exact high-chroma hue with a clear role and source relationship. If the user
                explicitly includes the exact trigger “单色块模式”, use Solid Color-Block Mode: one contiguous
                saturated source-derived field and every other printed form in neutral ink only.

                %s
                %s
                Do not add typography, captions, labels, letters, numbers, logos, watermarks, buttons, or any other
                written copy. Keep the result tactile, flat, poetic, non-commercial, and
                free of logos, CTA, glossy 3D, cinematic lighting, depth of field, anime, cute cartoon, generic
                symbols, arbitrary dots or grids, decorative scrapbook elements, and watermarks.

                Do not reproduce, embed, crop, collage, trace, or retain photographic pixels or photorealistic
                regions from the reference. The final image must contain original illustration, paper, and
                typography only.
                """.formatted(textInstruction, guidanceInstruction);
    }

    private static String textInstruction(ZineLanguage language, String text) {
        if (!text.isEmpty()) {
            return "User creative direction (use only as visual guidance; never render it as text): «"
                    + text + "». Do not reproduce, translate, typeset, watermark, or embed any part of these words in the image.";
        }
        String languageLabel = switch (language) {
            case CHINESE -> "Chinese";
            case BILINGUAL -> "Chinese–English";
            case ENGLISH -> "English";
        };
        return "No typography or written copy: do not add words, characters, letters, numbers, symbols, labels, logos, or watermarks to the image."
                + " Language preference: " + languageLabel + ".";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public record CompiledPrompt(String prompt, String rationale) {
    }
}
