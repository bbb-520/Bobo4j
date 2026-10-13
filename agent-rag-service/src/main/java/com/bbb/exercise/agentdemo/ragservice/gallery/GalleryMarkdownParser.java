package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.springframework.ai.document.Document;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/** CommonMark parses fences before headings, so headings inside prompts are preserved. */
public final class GalleryMarkdownParser {
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)|<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_PATH = Pattern.compile("!\\[[^\\]]*]\\(([^)]+)\\)|<img\\b[^>]*?src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZE = Pattern.compile("\\b\\d{2,5}[x×]\\d{2,5}\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> LABELS = Set.of("prompt", "positive prompt", "negative prompt", "notes", "metadata", "parameters", "images", "image", "output", "input");
    private final Parser parser = Parser.builder().build();

    public GalleryParseReport parseRepository(Path repository, String commit) throws IOException {
        Path root = repository.toRealPath();
        Path index;
        try (var paths = Files.walk(root, 8)) {
            var indexes = paths.filter(p -> p.getFileName().toString().equals("gallery.md") && Files.isRegularFile(p)).sorted().toList();
            if (indexes.size() != 1) throw new IOException("Expected exactly one gallery.md index under configured repository");
            index = indexes.getFirst();
        }
        var destinations = new LinkedHashSet<String>();
        parser.parse(Files.readString(index)).accept(new AbstractVisitor() {
            @Override public void visit(Link link) {
                if (link.getDestination().matches("gallery-[a-z0-9-]+\\.md(?:#.*)?")) destinations.add(link.getDestination().split("#", 2)[0]);
                super.visit(link);
            }
        });
        if (destinations.isEmpty()) throw new IOException("gallery.md contains no local gallery category links");
        var documents = new LinkedHashMap<String, Document>();
        var issues = new ArrayList<GalleryParseReport.Issue>();
        int categories = 0, entries = 0, duplicates = 0;
        for (String target : destinations) {
            Path file = index.getParent().resolve(target).normalize();
            if (!Files.isRegularFile(file) || !file.toRealPath().startsWith(root)) {
                issues.add(new GalleryParseReport.Issue(target, "", "MISSING_SOURCE", "Linked category file is missing or outside configured repository"));
                continue;
            }
            if (Files.size(file) > 2_000_000) throw new IOException("Category Markdown exceeds 2 MB limit: " + target);
            String category = target.substring("gallery-".length(), target.length() - 3);
            var report = parse(category, root.relativize(file).toString().replace('\\', '/'), Files.readString(file), commit);
            categories++;
            entries += report.entries(); duplicates += report.duplicates(); issues.addAll(report.issues());
            for (var document : report.documents()) if (documents.putIfAbsent(document.getId(), document) != null) duplicates++;
        }
        return new GalleryParseReport(new ArrayList<>(documents.values()), categories, entries, duplicates, issues);
    }

    public GalleryParseReport parse(String category, String source, String markdown, String commit) {
        var documents = new LinkedHashMap<String, Document>();
        var issues = new ArrayList<GalleryParseReport.Issue>();
        Entry entry = null;
        int entries = 0, duplicates = 0;
        for (Node node = parser.parse(markdown).getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading heading) {
                String title = text(heading).strip();
                String label = title.toLowerCase(Locale.ROOT).replaceAll(":$", "");
                if (entry != null && LABELS.contains(label)) {
                    flushUnfenced(entry, category, source, commit, documents, issues);
                    entry.readingPrompt = label.equals("prompt") || label.equals("positive prompt");
                    continue;
                }
                if (heading.getLevel() >= 2 && (entry == null || heading.getLevel() <= entry.level || title.matches("(?i)^No\\.\\s*\\d+.*"))) {
                    finish(entry, category, source, commit, documents, issues);
                    entry = new Entry(title, heading.getLevel()); entries++;
                    continue;
                }
            }
            if (entry == null) continue;
            if (node instanceof FencedCodeBlock block) {
                flushUnfenced(entry, category, source, commit, documents, issues);
                if (block.getClosingFenceLength() == null) {
                    issues.add(issue(source, entry, "UNCLOSED_FENCE", "Truncated fenced prompt was skipped")); continue;
                }
                if (!Set.of("", "text", "plaintext", "prompt", "json", "markdown", "md").contains(block.getInfo().strip().toLowerCase(Locale.ROOT))) {
                    issues.add(issue(source, entry, "NON_PROMPT_CODE", "Non-prompt code fence skipped: " + block.getInfo())); continue;
                }
                add(entry, block.getLiteral(), category, source, commit, documents, issues);
            } else if (entry.readingPrompt) {
                if (!(node instanceof HtmlBlock)) entry.unfenced.append(render(node)).append('\n');
                collectImages(entry, render(node));
            } else {
                String rendered = render(node);
                collectImages(entry, rendered);
                metadata(entry, rendered);
            }
        }
        finish(entry, category, source, commit, documents, issues);
        for (var issue : issues) if (issue.code().equals("DUPLICATE")) duplicates++;
        return new GalleryParseReport(new ArrayList<>(documents.values()), 1, entries, duplicates, issues);
    }

    private static void finish(Entry entry, String category, String source, String commit,
                               Map<String, Document> documents, List<GalleryParseReport.Issue> issues) {
        if (entry == null) return;
        flushUnfenced(entry, category, source, commit, documents, issues);
        if (entry.prompts == 0) issues.add(issue(source, entry, "NO_PROMPT", "Entry contains no complete prompt body"));
    }

    private static void flushUnfenced(Entry e, String category, String source, String commit,
                                      Map<String, Document> documents, List<GalleryParseReport.Issue> issues) {
        if (!e.unfenced.isEmpty()) {
            add(e, e.unfenced.toString(), category, source, commit, documents, issues);
            e.unfenced.setLength(0);
        }
        e.readingPrompt = false;
    }

    private static void add(Entry e, String body, String category, String source, String commit,
                             Map<String, Document> documents, List<GalleryParseReport.Issue> issues) {
        collectImages(e, body);
        String clean = IMAGE.matcher(body).replaceAll("").replace("\r\n", "\n").strip();
        if (clean.isBlank()) { issues.add(issue(source, e, "EMPTY_PROMPT", "Empty prompt skipped")); return; }
        e.prompts++;
        var metadata = new LinkedHashMap<String, Object>(e.metadata);
        metadata.put("category", category); metadata.put("source_file", source); metadata.put("title", e.title);
        metadata.put("variant", e.prompts); metadata.put("source_repository", GallerySource.REPOSITORY);
        metadata.put("source_commit", commit); metadata.put("license", GallerySource.LICENSE);
        metadata.put("attribution", GallerySource.ATTRIBUTION); metadata.put("content_hash", hash(clean));
        metadata.put("image_urls", new ArrayList<>(e.images));
        metadata.putIfAbsent("tags", List.of()); metadata.putIfAbsent("size", ""); metadata.putIfAbsent("quality", "");
        for (String field : List.of("size", "quality", "tags", "image_urls")) {
            Object value = metadata.get(field);
            if (value == null || value.equals("") || value instanceof Collection<?> c && c.isEmpty())
                issues.add(issue(source, e, "MISSING_FIELD", field + " is not declared by upstream; retained empty"));
        }
        String id = UUID.nameUUIDFromBytes((category + "\n" + clean).getBytes(StandardCharsets.UTF_8)).toString();
        if (documents.putIfAbsent(id, new Document(id, clean, metadata)) != null)
            issues.add(issue(source, e, "DUPLICATE", "Repeated content in category skipped; stable id " + id));
    }

    private static void metadata(Entry entry, String block) {
        for (String raw : block.split("\\R")) {
            String line = raw.replace("**", "").replace("`", "").strip().replaceFirst("^[-*]\\s+", "");
            var size = SIZE.matcher(line);
            if (line.matches("(?i)^(metadata|output metadata|size):.*") && size.find()) entry.metadata.put("size", size.group().replace('×', 'x'));
            if (line.matches("(?i)^(metadata|output metadata):.*")) {
                entry.metadata.put("raw_metadata", line.substring(line.indexOf(':') + 1).strip());
                for (String token : line.substring(line.indexOf(':') + 1).split("[·|]")) {
                    String part = token.strip();
                    if (Set.of("high", "medium", "low", "auto").contains(part.toLowerCase(Locale.ROOT))) entry.metadata.put("quality", part.toLowerCase(Locale.ROOT));
                    if (Set.of("portrait", "landscape", "square", "tall", "wide").contains(part)) entry.metadata.put("orientation", part);
                    if (part.equalsIgnoreCase("Curated")) entry.metadata.put("curated", true);
                    if (part.startsWith("Author:")) entry.metadata.put("author", part.substring(7).strip());
                }
            }
            if (line.matches("(?i)^quality:.*")) entry.metadata.put("quality", line.substring(line.indexOf(':') + 1).strip());
            if (line.matches("(?i)^tags:.*")) entry.metadata.put("tags", Arrays.stream(line.substring(line.indexOf(':') + 1).split("[,，;]")).map(String::strip).filter(s -> !s.isEmpty()).toList());
            if (line.matches("(?i)^author:.*")) entry.metadata.put("author", line.substring(line.indexOf(':') + 1).strip());
            if (line.matches("(?i)^(image|input image|output image|input reference):.*")) {
                String path = line.substring(line.indexOf(':') + 1).split("[·|]", 2)[0].strip();
                if (!path.isEmpty()) entry.images.add(path);
            }
            if (line.contains("Source:")) entry.metadata.put("source_attribution", line.substring(line.indexOf("Source:") + 7).strip());
        }
    }

    private static void collectImages(Entry entry, String raw) {
        var matcher = IMAGE_PATH.matcher(raw);
        while (matcher.find()) entry.images.add(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
    }

    private static String text(Node node) {
        var out = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override public void visit(Text text) { out.append(text.getLiteral()); }
            @Override public void visit(Code code) { out.append(code.getLiteral()); }
            @Override public void visit(SoftLineBreak br) { out.append('\n'); }
            @Override public void visit(HardLineBreak br) { out.append('\n'); }
        });
        return out.toString();
    }

    private static String render(Node node) {
        if (node instanceof HtmlBlock h) return h.getLiteral();
        if (node instanceof Heading h) return "#".repeat(h.getLevel()) + " " + text(h);
        if (node instanceof Image image) return "![" + text(image) + "](" + image.getDestination() + ")";
        if (node instanceof Link link) return "[" + text(link) + "](" + link.getDestination() + ")";
        if (node instanceof Text t) return t.getLiteral();
        if (node instanceof Code c) return c.getLiteral();
        if (node instanceof HtmlInline h) return h.getLiteral();
        if (node instanceof SoftLineBreak || node instanceof HardLineBreak) return "\n";
        var out = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            out.append(render(child));
            if (child instanceof ListItem || child instanceof Paragraph) out.append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static GalleryParseReport.Issue issue(String source, Entry entry, String code, String detail) {
        return new GalleryParseReport.Issue(source, entry.title, code, detail);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static final class Entry {
        final String title; final int level; int prompts; boolean readingPrompt;
        final Map<String, Object> metadata = new LinkedHashMap<>();
        final Set<String> images = new LinkedHashSet<>();
        final StringBuilder unfenced = new StringBuilder();
        Entry(String title, int level) { this.title = title; this.level = level; }
    }
}
