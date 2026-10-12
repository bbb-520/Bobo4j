package com.bbb.exercise.agentdemo.contentservice.zine;

import java.util.Locale;

/** Zine 内容语言选项，支持英文、中文和双语配置解析。 */
public enum ZineLanguage {
    ENGLISH,
    CHINESE,
    BILINGUAL;

    public static ZineLanguage parse(String v) {
        if (v == null || v.isBlank()) {
            return ENGLISH;
        }

        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "en", "english" -> ENGLISH;
            case "zh", "chinese", "中文" -> CHINESE;
            case "bilingual", "zh-en", "中英", "中英双语" -> BILINGUAL;
            default -> throw new IllegalArgumentException("language 只能是 en、zh 或 bilingual");
        };
    }
}
