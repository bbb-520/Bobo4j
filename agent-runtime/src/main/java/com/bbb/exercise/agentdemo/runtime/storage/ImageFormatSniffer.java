package com.bbb.exercise.agentdemo.runtime.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 图片结果格式嗅探：只看文件头部的有限字节，判定真实类型与像素尺寸。
 *
 * <p>供应商回传的 data URL 媒体类型和 HTTP {@code Content-Type} 都是不可信声明，
 * 因此落盘后统一以字节特征为准。整个判定最多读取 {@link #HEADER_BYTES} 字节，
 * 不解码像素、不分配与文件大小相当的数组，避免校验本身成为资源风险。
 */
final class ImageFormatSniffer {

    /** 判定所需的头部上限；JPEG 的 SOF 段可能位于较大的 EXIF 之后。 */
    static final int HEADER_BYTES = 64 * 1024;

    /** 单边像素上限，超过即视为异常结果。 */
    static final int MAX_EDGE = 20_000;

    /** 总像素上限，拦截"体积小但维度极大"的解压炸弹。 */
    static final long MAX_PIXELS = 50_000_000L;

    private ImageFormatSniffer() {}

    /** 识别到的图片格式；未知维度时宽高为 0，表示不做像素校验。 */
    record ImageFormat(String mime, int width, int height) {

        boolean exceedsLimits() {
            if (width <= 0 || height <= 0) {
                return false;
            }

            return width > MAX_EDGE
                    || height > MAX_EDGE
                    || (long) width * height > MAX_PIXELS;
        }
    }

    /**
     * 按文件头识别格式。
     *
     * @return 受支持的图片格式；无法识别时返回 {@code null}
     */
    static ImageFormat detect(Path file) throws IOException {
        byte[] header;

        try (InputStream input = Files.newInputStream(file)) {
            header = input.readNBytes(HEADER_BYTES);
        }

        if (header.length < 12) {
            return null;
        }

        if (isPng(header)) {
            return new ImageFormat("image/png", intBig(header, 16), intBig(header, 20));
        }

        if (isJpeg(header)) {
            return jpeg(header);
        }

        if (isGif(header)) {
            return new ImageFormat("image/gif", shortLittle(header, 6), shortLittle(header, 8));
        }

        if (isBmp(header)) {
            int width = intLittle(header, 18);
            int height = Math.abs(intLittle(header, 22));

            return new ImageFormat("image/bmp", width, height);
        }

        if (isWebp(header)) {
            return webp(header);
        }

        return null;
    }

    /** 归一化 data URL 声明的媒体类型，仅用于前置校验，不作为最终 MIME。 */
    static String normalizeDeclaredType(String declared) {
        if (declared == null || declared.isBlank()) {
            return "";
        }

        String value = declared.trim().toLowerCase(Locale.ROOT);

        return "image/jpg".equals(value) ? "image/jpeg" : value;
    }

    static boolean isAllowedDeclaredType(String declared) {
        return switch (normalizeDeclaredType(declared)) {
            case "image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp" -> true;
            default -> false;
        };
    }

    private static boolean isPng(byte[] h) {
        return h.length >= 24
                && (h[0] & 0xFF) == 0x89
                && h[1] == 'P'
                && h[2] == 'N'
                && h[3] == 'G'
                && h[4] == 0x0D
                && h[5] == 0x0A
                && h[6] == 0x1A
                && h[7] == 0x0A;
    }

    private static boolean isJpeg(byte[] h) {
        return (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
    }

    private static boolean isGif(byte[] h) {
        return h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8';
    }

    private static boolean isBmp(byte[] h) {
        return h[0] == 'B' && h[1] == 'M' && h.length >= 26;
    }

    private static boolean isWebp(byte[] h) {
        return h[0] == 'R'
                && h[1] == 'I'
                && h[2] == 'F'
                && h[3] == 'F'
                && h[8] == 'W'
                && h[9] == 'E'
                && h[10] == 'B'
                && h[11] == 'P';
    }

    /** 逐段跳过 JPEG 标记，遇到 SOF 段取尺寸；不支持渐进式以外的私有段时返回未知维度。 */
    private static ImageFormat jpeg(byte[] h) {
        int index = 2;

        while (index + 9 < h.length) {
            if ((h[index] & 0xFF) != 0xFF) {
                return new ImageFormat("image/jpeg", 0, 0);
            }

            int marker = h[index + 1] & 0xFF;

            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                index += 2;
                continue;
            }

            if (marker == 0xD9 || marker == 0xDA) {
                // 顺序数据之前没有 SOF：交给上层按未知维度处理。
                return new ImageFormat("image/jpeg", 0, 0);
            }

            int length = ((h[index + 2] & 0xFF) << 8) | (h[index + 3] & 0xFF);

            if (length < 2) {
                return new ImageFormat("image/jpeg", 0, 0);
            }

            boolean sof =
                    (marker >= 0xC0 && marker <= 0xC3)
                            || (marker >= 0xC5 && marker <= 0xC7)
                            || (marker >= 0xC9 && marker <= 0xCB)
                            || (marker >= 0xCD && marker <= 0xCF);

            if (sof) {
                int height = ((h[index + 5] & 0xFF) << 8) | (h[index + 6] & 0xFF);
                int width = ((h[index + 7] & 0xFF) << 8) | (h[index + 8] & 0xFF);

                return new ImageFormat("image/jpeg", width, height);
            }

            index += 2 + length;
        }

        return new ImageFormat("image/jpeg", 0, 0);
    }

    /** WebP 的三种子格式尺寸编码不同，只在能安全解析时返回维度。 */
    private static ImageFormat webp(byte[] h) {
        if (h.length < 30) {
            return new ImageFormat("image/webp", 0, 0);
        }

        boolean vp8x = h[12] == 'V' && h[13] == 'P' && h[14] == '8' && h[15] == 'X';

        if (vp8x) {
            int width = 1 + ((h[24] & 0xFF) | ((h[25] & 0xFF) << 8) | ((h[26] & 0xFF) << 16));
            int height = 1 + ((h[27] & 0xFF) | ((h[28] & 0xFF) << 8) | ((h[29] & 0xFF) << 16));

            return new ImageFormat("image/webp", width, height);
        }

        boolean vp8l = h[12] == 'V' && h[13] == 'P' && h[14] == '8' && h[15] == 'L';

        if (vp8l && h.length >= 25) {
            int bits = (h[21] & 0xFF) | ((h[22] & 0xFF) << 8) | ((h[23] & 0xFF) << 16) | ((h[24] & 0xFF) << 24);
            int width = (bits & 0x3FFF) + 1;
            int height = ((bits >> 14) & 0x3FFF) + 1;

            return new ImageFormat("image/webp", width, height);
        }

        // 有损 VP8 的尺寸藏在帧头压缩数据里，这里不做深度解析，保持校验成本有界。
        return new ImageFormat("image/webp", 0, 0);
    }

    private static int intBig(byte[] h, int offset) {
        return ((h[offset] & 0xFF) << 24)
                | ((h[offset + 1] & 0xFF) << 16)
                | ((h[offset + 2] & 0xFF) << 8)
                | (h[offset + 3] & 0xFF);
    }

    private static int intLittle(byte[] h, int offset) {
        return (h[offset] & 0xFF)
                | ((h[offset + 1] & 0xFF) << 8)
                | ((h[offset + 2] & 0xFF) << 16)
                | ((h[offset + 3] & 0xFF) << 24);
    }

    private static int shortLittle(byte[] h, int offset) {
        return (h[offset] & 0xFF) | ((h[offset + 1] & 0xFF) << 8);
    }
}
