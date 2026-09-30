package com.bbb.exercise.agentdemo1_0.zine;

import java.util.Locale;

/** Language choice for the small editorial text embedded in the poster. */
public enum ZineLanguage {

    ENGLISH,
    CHINESE,
    BILINGUAL;

    public static ZineLanguage parse(String value) {
        if (value == null || value.isBlank()) {
            return ENGLISH;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "en", "english" -> ENGLISH;
            case "zh", "chinese", "中文" -> CHINESE;
            case "bilingual", "zh-en", "中英", "中英双语" -> BILINGUAL;
            default -> throw new IllegalArgumentException("language 只能是 en、zh 或 bilingual");
        };
    }
}
