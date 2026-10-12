package com.bbb.exercise.agentdemo.contentservice.zine;

import com.bbb.exercise.agentdemo.contentservice.ZineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.Set;

/** Application service for one image-editing generation request. */
@Service
@RequiredArgsConstructor
public class ZineGenerationService {

    private static final Set<String> IMAGE_TYPES =
            Set.of(
                    MediaType.IMAGE_JPEG_VALUE,
                    MediaType.IMAGE_PNG_VALUE,
                    "image/webp",
                    "image/bmp",
                    "image/tiff",
                    "image/gif");

    private final ZinePromptCompiler promptCompiler;
    private final ZineImageGenerationClient imageGenerationClient;
    private final ZineProperties properties;

    // 1. 上传图片创作：校验图片输入，编译风格提示词并调用图片供应商。
    public Mono<ZineGenerationResponse> generate(
            byte[] source,
            String contentType,
            String modeValue,
            String languageValue,
            String text,
            String guidance,
            String apiKey) {
        validateSource(source, contentType);
        ZineMode mode = ZineMode.parse(modeValue);
        ZineLanguage language = ZineLanguage.parse(languageValue);
        ZinePromptCompiler.CompiledPrompt compiled =
                promptCompiler.compile(mode, language, text, guidance);
        String imageDataUrl =
                "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(source);

        return imageGenerationClient
                .generate(imageDataUrl, compiled.prompt(), apiKey)
                .map(
                        result ->
                                new ZineGenerationResponse(
                                        mode.name().toLowerCase(),
                                        properties.getModel(),
                                        result.imageUrl(),
                                        compiled.rationale(),
                                        result.providerRequestId(),
                                        result.inputTokens(),
                                        result.outputTokens()));
    }

    // 2. 已有图片创作：复用 URL 输入路径，保持模式和提示词规则一致。
    /** OSS-backed path: pass a short-lived signed URL to the provider instead of a Base64 request body. */
    public Mono<ProviderGeneration> generateFromSourceUrl(
            String sourceUrl, String modeValue, String languageValue, String text, String apiKey) {
        ZineMode mode = ZineMode.parse(modeValue);
        ZineLanguage language = ZineLanguage.parse(languageValue);
        ZinePromptCompiler.CompiledPrompt compiled =
                promptCompiler.compile(mode, language, text, null);

        return imageGenerationClient
                .generate(sourceUrl, compiled.prompt(), apiKey)
                .map(result -> new ProviderGeneration(result, compiled.rationale()));
    }

    // 3. 输入约束：限制图片类型和体积，防止非法文件进入模型请求。
    private void validateSource(byte[] source, String contentType) {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("请上传一张图片");
        }

        if (source.length > properties.getMaxUploadBytes()) {
            throw new IllegalArgumentException(
                    "图片不能超过 " + properties.getMaxUploadBytes() / 1024 / 1024 + " MB");
        }

        if (contentType == null || !IMAGE_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("仅支持 JPG、PNG、WEBP、BMP、TIFF 或 GIF 图片");
        }
    }

    /** Zine 生成响应，汇总版式模式、图片地址、生成依据和供应商用量。 */
    public record ZineGenerationResponse(
            String mode,
            String model,
            String imageUrl,
            String rationale,
            String providerRequestId,
            Long inputTokens,
            Long outputTokens) {}

    /** 供应商生成的中间结果，将图片回执与提示词编译依据关联。 */
    public record ProviderGeneration(
            ZineImageGenerationClient.ZineImageResult result, String rationale) {}
}
