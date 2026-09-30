package com.bbb.exercise.agentdemo1_0.zine;

import com.bbb.exercise.agentdemo1_0.config.ZineProperties;
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

    private static final Set<String> IMAGE_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE,
            MediaType.IMAGE_PNG_VALUE,
            "image/webp",
            "image/bmp",
            "image/tiff",
            "image/gif");

    private final ZinePromptCompiler promptCompiler;
    private final ZineImageGenerationClient imageGenerationClient;
    private final ZineProperties properties;

    public Mono<ZineGenerationResponse> generate(byte[] source, String contentType,
                                                 String modeValue, String languageValue,
                                                 String text, String guidance, String apiKey) {
        validateSource(source, contentType);
        ZineMode mode = ZineMode.parse(modeValue);
        ZineLanguage language = ZineLanguage.parse(languageValue);
        ZinePromptCompiler.CompiledPrompt compiled = promptCompiler.compile(mode, language, text, guidance);
        String imageDataUrl = "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(source);

        return imageGenerationClient.generate(imageDataUrl, compiled.prompt(), apiKey)
                .map(result -> new ZineGenerationResponse(
                        mode.name().toLowerCase(),
                        properties.getModel(),
                        result.imageUrl(),
                        compiled.rationale(),
                        result.providerRequestId()));
    }

    /** OSS-backed path: pass a short-lived signed URL to the provider instead of a Base64 request body. */
    public Mono<ProviderGeneration> generateFromSourceUrl(String sourceUrl, String modeValue,
                                                           String languageValue, String text, String apiKey) {
        ZineMode mode = ZineMode.parse(modeValue);
        ZineLanguage language = ZineLanguage.parse(languageValue);
        ZinePromptCompiler.CompiledPrompt compiled = promptCompiler.compile(mode, language, text, null);
        return imageGenerationClient.generate(sourceUrl, compiled.prompt(), apiKey)
                .map(result -> new ProviderGeneration(result, compiled.rationale()));
    }

    private void validateSource(byte[] source, String contentType) {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("请上传一张图片");
        }
        if (source.length > properties.getMaxUploadBytes()) {
            throw new IllegalArgumentException("图片不能超过 " + properties.getMaxUploadBytes() / 1024 / 1024 + " MB");
        }
        if (contentType == null || !IMAGE_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("仅支持 JPG、PNG、WEBP、BMP、TIFF 或 GIF 图片");
        }
    }

    public record ZineGenerationResponse(String mode, String model, String imageUrl,
                                         String rationale, String providerRequestId) {
    }

    public record ProviderGeneration(ZineImageGenerationClient.ZineImageResult result, String rationale) {
    }
}
