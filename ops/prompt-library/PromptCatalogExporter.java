import com.bbb.exercise.agentdemo.ragservice.gallery.GalleryMarkdownParser;
import com.bbb.exercise.agentdemo.ragservice.gallery.GalleryParseReport;
import com.bbb.exercise.agentdemo.ragservice.gallery.GallerySource;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Offline export only. The launcher loads the existing parser, never a Spring context. */
public final class PromptCatalogExporter {
    private static final String PINNED_COMMIT = "9f8aa1a44a4a5c0f5a5d6e45eed564b3e2d5b87f";

    public static void main(String[] args) throws Exception {
        if (args.length < 5 || args.length > 6 || args.length == 6 && !args[5].equals("--check"))
            throw new IllegalArgumentException("Expected source-repository output-catalog license-directory categories-tsv source-fingerprints [--check]");
        Path source = Path.of(args[0]).toRealPath();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Path licenses = Path.of(args[2]).toAbsolutePath().normalize();
        boolean check = args.length == 6;
        GallerySource.verifyCommit(source, PINNED_COMMIT);
        verifySourceFingerprints(source, Path.of(args[4]));
        var categories = readCategories(Path.of(args[3]));
        var report = new GalleryMarkdownParser().parseRepository(source, PINNED_COMMIT);
        verifyReport(report, categories);
        List<Map<String, Object>> prompts = report.documents().stream().map(PromptCatalogExporter::exportPrompt).toList();
        var catalog = ordered("source", ordered("repository", GallerySource.REPOSITORY, "commit", PINNED_COMMIT,
                        "license", GallerySource.LICENSE, "attribution", GallerySource.ATTRIBUTION),
                "categories", categories, "prompts", prompts);
        byte[] catalogBytes = (json(catalog, 0) + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] licenseBytes = Files.readAllBytes(source.resolve("LICENSE"));
        byte[] noticeBytes = ("Prompt Library third-party notice\n\n"
                + "Source: " + GallerySource.REPOSITORY + "\n"
                + "Pinned commit: " + PINNED_COMMIT + "\n"
                + "License: MIT (see GPT-Image2-Skill-LICENSE.txt)\n"
                + GallerySource.ATTRIBUTION + "\n\n"
                + "This catalog contains all 164 prompt bodies in 31 categories (163 numbered entries).\n"
                + "Original prompt bodies, stable IDs, declared metadata, author and source references\n"
                + "are exported using the existing GalleryMarkdownParser. Chinese category labels and\n"
                + "search keywords are editorial translations, separate from original prompt tags.\n"
                + "The parser normalizes CRLF to LF and removes enclosing fence/outer whitespace.\n"
                + "Individual author and source references are retained from the upstream documents;\n"
                + "the repository license notice does not replace those attribution markers.\n"
                + "Upstream preview images are not distributed or fetched by this export.\n").getBytes(StandardCharsets.UTF_8);
        if (check) {
            requireSame(output, catalogBytes);
            requireSame(licenses.resolve("GPT-Image2-Skill-LICENSE.txt"), licenseBytes);
            requireSame(licenses.resolve("NOTICE.txt"), noticeBytes);
        } else {
            write(output, catalogBytes);
            write(licenses.resolve("GPT-Image2-Skill-LICENSE.txt"), licenseBytes);
            write(licenses.resolve("NOTICE.txt"), noticeBytes);
        }
        System.out.printf("%s catalog: 164 prompts, 31 categories, 163 numbered entries, 0 duplicates; commit=%s; sha256=%s%n",
                check ? "Verified" : "Exported", PINNED_COMMIT, hash(catalogBytes));
        System.out.printf("Undeclared metadata retained empty: %d parser diagnostics (MISSING_FIELD only).%n", report.issues().size());
    }

    private static void verifySourceFingerprints(Path source, Path manifest) throws Exception {
        int checked = 0;
        Set<String> paths = new LinkedHashSet<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] pair = line.split("  ", 2);
            if (pair.length != 2 || !pair[0].matches("[0-9a-f]{64}") || !paths.add(pair[1]))
                throw new IOException("Invalid source fingerprint manifest");
            Path file = source.resolve(pair[1]).normalize();
            if (!file.startsWith(source) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(source))
                throw new IOException("Missing or unsafe source fingerprint path: " + pair[1]);
            String normalized = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (!hash(normalized.getBytes(StandardCharsets.UTF_8)).equals(pair[0]))
                throw new IOException("Source hash mismatch; pinned source file is locally modified: " + pair[1]);
            checked++;
        }
        if (checked != 33 || !paths.contains("LICENSE") || !paths.contains("skills/gpt-image/references/gallery.md"))
            throw new IOException("Source fingerprint manifest must cover LICENSE, gallery index and all 31 category files");
    }

    private static List<Map<String, Object>> readCategories(Path file) throws IOException {
        var categories = new ArrayList<Map<String, Object>>();
        Set<String> ids = new LinkedHashSet<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 3 || !fields[0].matches("[a-z0-9-]+") || fields[1].isBlank() || !ids.add(fields[0]))
                throw new IOException("Invalid or duplicate translated category");
            List<String> keywords = List.of(fields[2].split(",", -1));
            if (keywords.size() < 3 || keywords.stream().anyMatch(String::isBlank))
                throw new IOException("Each category needs nonempty editorial search keywords");
            categories.add(ordered("id", fields[0], "label", fields[1], "keywords", keywords));
        }
        if (categories.size() != 31) throw new IOException("Expected all 31 translated categories");
        return List.copyOf(categories);
    }

    private static void verifyReport(GalleryParseReport report, List<Map<String, Object>> categories) throws Exception {
        if (report.duplicates() != 0) throw new IOException("Duplicate source prompts are not permitted");
        if (report.categories() != 31 || report.entries() != 163 || report.documents().size() != 164)
            throw new IOException("Incomplete source gallery; expected 31 categories, 163 entries and 164 prompts");
        for (var issue : report.issues())
            if (!issue.code().equals("MISSING_FIELD")) throw new IOException("Unexpected parser diagnostic: " + issue);
        Set<String> ids = new LinkedHashSet<>();
        Set<String> expectedCategories = new LinkedHashSet<>();
        for (var category : categories) expectedCategories.add((String) category.get("id"));
        Set<String> actualCategories = new LinkedHashSet<>();
        for (Document doc : report.documents()) {
            var metadata = doc.getMetadata();
            String body = doc.getText();
            String category = string(metadata, "category");
            actualCategories.add(category);
            if (body == null || body.isBlank() || !ids.add(doc.getId())) throw new IOException("Empty body or duplicate stable prompt ID");
            if (!PINNED_COMMIT.equals(metadata.get("source_commit")) || !GallerySource.REPOSITORY.equals(metadata.get("source_repository")))
                throw new IOException("Unexpected prompt source provenance");
            if (!hash(body.getBytes(StandardCharsets.UTF_8)).equals(metadata.get("content_hash")))
                throw new IOException("Prompt content hash mismatch: " + doc.getId());
            String expectedId = UUID.nameUUIDFromBytes((category + "\n" + body).getBytes(StandardCharsets.UTF_8)).toString();
            if (!expectedId.equals(doc.getId())) throw new IOException("Unstable prompt ID: " + doc.getId());
            if (!(metadata.get("variant") instanceof Number n) || n.intValue() < 1)
                throw new IOException("Invalid source variant: " + doc.getId());
        }
        if (!actualCategories.equals(expectedCategories)) throw new IOException("Category translation mapping differs from source categories");
    }

    private static Map<String, Object> exportPrompt(Document doc) {
        var metadata = doc.getMetadata();
        var tags = metadata.get("tags");
        if (!(tags instanceof Collection<?> list) || list.stream().anyMatch(tag -> !(tag instanceof String)))
            throw new IllegalArgumentException("Invalid original tags for " + doc.getId());
        return ordered("id", doc.getId(), "title", string(metadata, "title"), "category", string(metadata, "category"),
                "content", doc.getText(), "size", string(metadata, "size"), "quality", string(metadata, "quality"),
                "tags", List.copyOf((Collection<?>) tags), "sourceFile", string(metadata, "source_file"),
                "sourceRepository", string(metadata, "source_repository"), "sourceCommit", string(metadata, "source_commit"),
                "author", string(metadata, "author"), "attribution", string(metadata, "attribution"),
                "sourceAttribution", string(metadata, "source_attribution"), "variant", metadata.get("variant"),
                "contentHash", string(metadata, "content_hash"));
    }

    private static String string(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null) return "";
        if (!(value instanceof String text)) throw new IllegalArgumentException("Expected original string metadata: " + key);
        return text;
    }

    private static LinkedHashMap<String, Object> ordered(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void requireSame(Path file, byte[] expected) throws IOException {
        if (!Files.isRegularFile(file) || !java.util.Arrays.equals(Files.readAllBytes(file), expected))
            throw new IOException("Catalog check differs from pinned export: " + file);
    }

    private static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".prompt-catalog-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private static String json(Object value, int depth) {
        if (value instanceof String text) return quote(text);
        if (value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var entries = new ArrayList<String>();
            for (var entry : map.entrySet()) entries.add("  ".repeat(depth + 1) + quote((String) entry.getKey()) + ": " + json(entry.getValue(), depth + 1));
            return "{\n" + String.join(",\n", entries) + "\n" + "  ".repeat(depth) + "}";
        }
        if (value instanceof Collection<?> list) {
            if (list.isEmpty()) return "[]";
            var items = new ArrayList<String>();
            for (Object item : list) items.add("  ".repeat(depth + 1) + json(item, depth + 1));
            return "[\n" + String.join(",\n", items) + "\n" + "  ".repeat(depth) + "]";
        }
        throw new IllegalArgumentException("Unsupported catalog JSON value: " + value);
    }

    private static String quote(String text) {
        var out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
}
