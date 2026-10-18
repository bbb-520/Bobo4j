package com.bbb.exercise.agentdemo.contentservice.zine;

import com.bbb.exercise.agentdemo.contentservice.ZineCompositionProperties;
import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient.ZineRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/** Deterministic composition only. Media owns provider execution and durable recovery. */
@Service
@RequiredArgsConstructor
public class ZineGenerationService {
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg","image/png","image/webp","image/bmp","image/tiff","image/gif");
    private final ZinePromptCompiler promptCompiler;
    private final ZineCompositionProperties properties;
    public ZineRequest compose(String requestId, byte[] source, String contentType,
            String modeValue, String languageValue, String text, String guidance) {
        if (source == null || source.length == 0) throw new IllegalArgumentException("请上传一张图片");
        if (source.length > properties.getMaxUploadBytes()) throw new IllegalArgumentException("图片超过上传限制");
        if (contentType == null || !IMAGE_TYPES.contains(contentType.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("仅支持 JPG、PNG、WEBP、BMP、TIFF 或 GIF 图片");
        if ((text != null && text.length() > 4000) || (guidance != null && guidance.length() > 4000))
            throw new IllegalArgumentException("创作方向不能超过 4000 字符");
        var mode = ZineMode.parse(modeValue); var language = ZineLanguage.parse(languageValue);
        var compiled = promptCompiler.compile(mode,language,text,guidance);
        return new ZineRequest(requestId,"data:" + contentType.toLowerCase(Locale.ROOT) + ";base64," + Base64.getEncoder().encodeToString(source),
                mode.name().toLowerCase(Locale.ROOT),language.name().toLowerCase(Locale.ROOT),compiled.prompt(),compiled.rationale());
    }
    /** Historical receipt schema and stable public response. */
    public record ZineGenerationResponse(String mode, String model, String imageUrl, String rationale,
            String providerRequestId, Long inputTokens, Long outputTokens) {}
}
