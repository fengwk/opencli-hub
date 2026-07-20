package fun.fengwk.openclihub.core.execution.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.openclihub.core.resource.model.HubExecutionResourceGroup;
import fun.fengwk.openclihub.core.resource.util.HubResourcePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Imports allowlisted local artifact paths discovered in OpenCLI JSON stdout into the
 * per-execution resource group so callers only consume Hub resource download URLs.
 *
 * <p>This intentionally does not expose arbitrary path download. Only regular files under
 * configured allowlisted roots (OpenCLI home, user home Pictures/Downloads, and the
 * execution group itself) are copied.
 */
@Component
public class HubExecutionArtifactImporter {

    private static final Logger log = LoggerFactory.getLogger(HubExecutionArtifactImporter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Copy allowlisted paths referenced by stdout into {@code group}. Best-effort: failures
     * for individual files are logged and skipped so a partial import still returns scanned
     * resources.
     */
    public void importFromStdout(HubExecutionResourceGroup group, String stdout) {
        if (group == null || group.getRealPath() == null || stdout == null || stdout.isBlank()) {
            return;
        }
        Path groupDir = group.getRealPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(groupDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Set<Path> candidates = extractLocalPaths(stdout);
        if (candidates.isEmpty()) {
            return;
        }
        List<Path> allowRoots = allowlistedRoots(groupDir);
        for (Path source : candidates) {
            try {
                importOne(source, groupDir, allowRoots);
            } catch (Exception ex) {
                log.warn("Skip importing artifact {} for execution {}: {}",
                    source, group.getExecutionId(), ex.toString());
            }
        }
    }

    private void importOne(Path source, Path groupDir, List<Path> allowRoots) throws IOException {
        if (source == null) {
            return;
        }
        Path abs = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(abs)) {
            return;
        }
        if (!Files.isRegularFile(abs, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!isUnderAllowlistedRoot(abs, allowRoots)) {
            log.info("Refuse importing non-allowlisted artifact path: {}", abs);
            return;
        }
        // Already inside the execution group — leave for scan.
        if (abs.startsWith(groupDir)) {
            return;
        }
        String reserved = HubResourcePaths.reserveFileName(groupDir, abs.getFileName().toString());
        Path target = groupDir.resolve(reserved);
        Files.copy(abs, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        log.info("Imported execution artifact {} -> {}", abs, target);
    }

    static Set<Path> extractLocalPaths(String stdout) {
        Set<Path> paths = new LinkedHashSet<>();
        JsonNode root;
        try {
            root = JSON.readTree(stdout);
        } catch (Exception ex) {
            return paths;
        }
        if (root == null) {
            return paths;
        }
        if (root.isArray()) {
            for (JsonNode row : root) {
                collectFromRow(row, paths);
            }
        } else if (root.isObject()) {
            collectFromRow(root, paths);
        }
        return paths;
    }

    private static void collectFromRow(JsonNode row, Set<Path> paths) {
        if (row == null || !row.isObject()) {
            return;
        }
        collectPathNode(row.get("path"), paths);
        collectPathNode(row.get("filename"), paths);
        JsonNode downloads = row.get("downloads");
        if (downloads != null && downloads.isTextual()) {
            try {
                downloads = JSON.readTree(downloads.asText());
            } catch (Exception ignored) {
                downloads = null;
            }
        }
        if (downloads != null && downloads.isArray()) {
            for (JsonNode item : downloads) {
                if (item != null && item.isObject()) {
                    collectPathNode(item.get("path"), paths);
                    collectPathNode(item.get("filename"), paths);
                    collectPathNode(item.get("collectedFrom"), paths);
                }
            }
        }
        // Nested arrays sometimes used by adapters.
        Iterator<String> fields = row.fieldNames();
        while (fields.hasNext()) {
            String name = fields.next();
            if ("downloads".equals(name) || "files".equals(name) || "images".equals(name)) {
                continue;
            }
            JsonNode child = row.get(name);
            if (child != null && child.isArray()) {
                for (JsonNode item : child) {
                    if (item != null && item.isObject()) {
                        collectPathNode(item.get("path"), paths);
                    }
                }
            }
        }
    }

    private static void collectPathNode(JsonNode node, Set<Path> paths) {
        if (node == null || !node.isTextual()) {
            return;
        }
        String raw = node.asText().trim();
        if (raw.isEmpty() || raw.startsWith("http://") || raw.startsWith("https://")
            || raw.startsWith("sediment://") || raw.startsWith("sandbox:")) {
            return;
        }
        if (!(raw.startsWith("/") || raw.matches("^[A-Za-z]:[\\\\/].*"))) {
            return;
        }
        paths.add(Path.of(raw));
    }

    private static List<Path> allowlistedRoots(Path groupDir) {
        List<Path> roots = new ArrayList<>();
        roots.add(groupDir);
        addIfPresent(roots, Path.of("/var/lib/opencli"));
        String home = System.getenv("HOME");
        if (home != null && !home.isBlank()) {
            Path homePath = Path.of(home).toAbsolutePath().normalize();
            addIfPresent(roots, homePath);
            addIfPresent(roots, homePath.resolve("Pictures"));
            addIfPresent(roots, homePath.resolve("Downloads"));
            addIfPresent(roots, homePath.resolve("Pictures").resolve("chatgpt-agent"));
        }
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp != null && !tmp.isBlank()) {
            addIfPresent(roots, Path.of(tmp));
        }
        return roots;
    }

    private static void addIfPresent(List<Path> roots, Path path) {
        if (path == null) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        for (Path existing : roots) {
            if (existing.equals(normalized)) {
                return;
            }
        }
        roots.add(normalized);
    }

    private static boolean isUnderAllowlistedRoot(Path file, List<Path> roots) {
        Path abs = file.toAbsolutePath().normalize();
        for (Path root : roots) {
            if (abs.startsWith(root)) {
                // Disallow sensitive profile/config trees even under home.
                String lower = abs.toString().toLowerCase(Locale.ROOT);
                if (lower.contains("/.config/")
                    || lower.contains("/google-chrome/")
                    || lower.contains("/chromium/")
                    || lower.contains("/singleton")
                    || lower.contains("/default/cookies")
                    || lower.contains("/default/login data")) {
                    return false;
                }
                return true;
            }
        }
        return false;
    }
}
