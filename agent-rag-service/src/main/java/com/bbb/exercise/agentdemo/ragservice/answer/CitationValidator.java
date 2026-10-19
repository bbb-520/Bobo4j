package com.bbb.exercise.agentdemo.ragservice.answer;

import java.util.*;
import java.util.regex.Pattern;

/** 文档答案引用校验器，拒绝不存在的来源并将合法引用映射为来源视图。 */
public final class CitationValidator {
    private CitationValidator() {}

    private static final Pattern CITATION = Pattern.compile("\\[S([^\\]]*)\\]");

    // 1. 引用规则：校验来源编号范围和事实段落引用，返回实际使用的来源集合。
    public static Set<Integer> validate(String answer, int evidenceCount) {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("EMPTY_ANSWER");
        }

        var citations = new LinkedHashSet<Integer>();
        var match = CITATION.matcher(answer);

        while (match.find()) {
            String id = match.group(1);

            if (!id.matches("[1-9][0-9]*")) {
                throw new IllegalArgumentException("CITATION_INVALID");
            }

            int index;

            try {
                index = Integer.parseInt(id);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("CITATION_INVALID");
            }

            if (index > evidenceCount) {
                throw new IllegalArgumentException("CITATION_INVALID");
            }

            citations.add(index);
        }

        if (citations.isEmpty()) {
            throw new IllegalArgumentException("CITATION_MISSING");
        }

        if (!uncitedUnits(answer).isEmpty()) {
            throw new IllegalArgumentException("UNCITED_CLAIM");
        }

        return citations;
    }

    /** Bounded structural feedback contains no quoted document/model instructions. */
    public static String repairFeedback(String answer, int evidenceCount, String reason) {
        String allowed = "Use only actual evidence IDs [S1] through [S" + evidenceCount + "]. ";
        if ("UNCITED_CLAIM".equals(reason)) {
            return reason + ": missing citations in " + String.join(", ", uncitedUnits(answer).stream().limit(8).toList())
                    + ". Remove the uncited preamble and closing commentary. Cite each supported factual paragraph, list item and table row independently; remove claims the evidence cannot support. " + allowed;
        }
        if (Set.of("CITATION_INVALID", "CITATION_MISSING").contains(reason)) {
            return reason + ": " + allowed + "Attach the supporting source directly to each factual unit. Do not invent citations or cite a source for unsupported content.";
        }
        return reason + ": Regenerate a concise answer to the original question, retaining only claims supported by their cited evidence. Preserve conflicts and uncertainty. " + allowed;
    }

    private static List<String> uncitedUnits(String answer) {
        var missing = new ArrayList<String>();
        String[] paragraphs = answer.split("\\R\\s*\\R");
        for (int p = 0; p < paragraphs.length; p++) {
            String paragraph = paragraphs[p];
            String label = "paragraph " + (p + 1);
            String[] lines = paragraph.split("\\R");
            boolean table = Arrays.stream(lines).anyMatch(CitationValidator::tableSeparator);
            boolean list = Arrays.stream(lines).anyMatch(CitationValidator::listStart);
            if (table) {
                for (int i = 0; i < lines.length; i++) {
                    if (tableSeparator(lines[i]) || i + 1 < lines.length && tableSeparator(lines[i + 1])) continue;
                    if (!lines[i].isBlank() && !CITATION.matcher(lines[i]).find()) missing.add(label + " table row " + (i + 1));
                }
            } else if (list) {
                var unit = new StringBuilder();
                int item = 0;
                for (String line : lines) {
                    if (listStart(line)) {
                        if (!unit.isEmpty() && !CITATION.matcher(unit).find()
                                && (item > 0 || !sectionLabel(unit.toString()))) {
                            missing.add(item == 0 ? label : label + " list item " + item);
                        }
                        unit.setLength(0); item++;
                    }
                    if (!line.isBlank()) unit.append(line).append('\n');
                }
                if (!unit.isEmpty() && !CITATION.matcher(unit).find()) missing.add(label + " list item " + item);
            } else if (!paragraph.isBlank()
                    && !CITATION.matcher(paragraph).find()) {
                missing.add(label);
            }
        }
        return missing;
    }

    /** A one-line list label is formatting, not a length-based exemption for factual prose. */
    private static boolean sectionLabel(String text) {
        return text.strip().matches("[\\p{L} ]{1,20}[:：]");
    }

    private static boolean listStart(String line) {
        return line.matches("\\s*(?:[-+*]|[0-9]+[.)])\\s+.*");
    }

    private static boolean tableSeparator(String line) {
        String stripped = line.strip().replaceAll("^\\||\\|$", "");
        String[] cells = stripped.split("\\|", -1);
        return (cells.length > 1 || line.indexOf('|') >= 0)
                && Arrays.stream(cells).allMatch(c -> c.strip().matches(":?-{3,}:?"));
    }
}
