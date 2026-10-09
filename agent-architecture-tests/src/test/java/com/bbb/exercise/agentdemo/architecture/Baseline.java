package com.bbb.exercise.agentdemo.architecture;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 基线文件读取入口。
 *
 * <p>基线只记录"当前还不合规、但已经登记、正在收敛"的事实。
 * 门禁的判据统一是：
 * <ul>
 *     <li><b>立即变红</b>：违反目标态且今天已经为 0 的规则，不给基线，直接断言；</li>
 *     <li><b>基线快照</b>：存量违规，基线里登记一份，只拦截"新增"，不拦截"减少"。</li>
 * </ul>
 * 因此基线不会因为有人改好了而失败——它只会因为有人变差而失败。
 */
final class Baseline {
    private static final String ROOT = "/architecture-baseline/";

    private Baseline() {
    }

    /** 返回去掉注释与空行后的原始条目，保留 {@code |} 分列结构。 */
    static List<String> entries(String name) {
        InputStream stream = Baseline.class.getResourceAsStream(ROOT + name);
        if (stream == null) {
            throw new IllegalStateException("缺少基线文件 " + ROOT + name
                    + "；可先运行 BaselineDumpTest 查看当前状态并重建基线。");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 基线条目的第一列：用于"是否已登记"判断。 */
    static List<String> keys(String name) {
        return entries(name).stream().map(entry -> column(entry, 0)).toList();
    }

    /** 按 {@code |} 取第 index 列，越界返回空串。 */
    static String column(String entry, int index) {
        String[] parts = entry.split("\\|", -1);
        return index < parts.length ? parts[index].trim() : "";
    }
}
