package fun.fengwk.openclihub.core.resource.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Best-effort MIME type lookup used by the resource center. The OS probe is preferred; a
 * minimal extension fallback keeps the response stable on minimal containers.
 *
 * @author fengwk
 */
public final class HubResourceMimeTypes {

    private static final Map<String, String> EXT_MAP = Map.ofEntries(
        Map.entry("txt", "text/plain"),
        Map.entry("json", "application/json"),
        Map.entry("html", "text/html"),
        Map.entry("htm", "text/html"),
        Map.entry("css", "text/css"),
        Map.entry("js", "application/javascript"),
        Map.entry("csv", "text/csv"),
        Map.entry("pdf", "application/pdf"),
        Map.entry("zip", "application/zip"),
        Map.entry("xml", "application/xml"),
        Map.entry("png", "image/png"),
        Map.entry("jpg", "image/jpeg"),
        Map.entry("jpeg", "image/jpeg"),
        Map.entry("gif", "image/gif"),
        Map.entry("webp", "image/webp"),
        Map.entry("svg", "image/svg+xml"),
        Map.entry("mp4", "video/mp4"),
        Map.entry("webm", "video/webm"),
        Map.entry("mov", "video/quicktime"),
        Map.entry("mp3", "audio/mpeg"),
        Map.entry("wav", "audio/wav"),
        Map.entry("md", "text/markdown")
    );

    private HubResourceMimeTypes() {
    }

    /**
     * Detect the MIME type for the given file. Falls back to {@code application/octet-stream}.
     */
    public static String probe(Path file) {
        if (file == null) {
            return "application/octet-stream";
        }
        try {
            String detected = Files.probeContentType(file);
            if (detected != null && !detected.isBlank()) {
                return detected;
            }
        } catch (IOException ignored) {
            // probeContentType may fail on some platforms; fall through to extension lookup.
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String mime = EXT_MAP.get(ext);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

}
