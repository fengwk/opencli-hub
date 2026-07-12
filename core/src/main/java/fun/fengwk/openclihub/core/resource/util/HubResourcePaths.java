package fun.fengwk.openclihub.core.resource.util;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.resource.HubResourceSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Virtual and real path conversions for the file-system resource center.
 * <p>
 * Virtual paths are strictly {@code /resources/{yyyy-MM-dd}/{group}/{relativePath}}.
 * Any input that does not fit the contract, escapes the configured root, contains
 * traversals, references symlinks, or maps outside a date/group directory is rejected.
 *
 * @author fengwk
 */
public final class HubResourcePaths {

    /** Virtual path prefix required by the technical design. */
    public static final String VIRTUAL_PREFIX = "/resources/";

    /** UTC ISO-8601 calendar date used for daily directories. */
    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Reject empty segments or path separators anywhere in an upload file name. */
    private static final Pattern UPLOAD_FILE_NAME_INVALID = Pattern.compile("[\\\\/\\u0000]");

    private static final Pattern GROUP_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._\\-]{0,127}");

    private static final Pattern RELATIVE_SEGMENT_PATTERN = Pattern.compile("[A-Za-z0-9._\\-]+");

    private HubResourcePaths() {
    }

    /**
     * Build the real root directory for the resource center from {@link OpenCliHubProperties}.
     */
    public static Path resourceRoot(OpenCliHubProperties properties) {
        if (properties == null || properties.getResource() == null) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        String root = properties.getResource().getRootDir();
        return requireAbsolute(Path.of(root == null || root.isBlank() ? "/" : root).normalize());
    }

    /**
     * Build a real date directory under the resource root.
     */
    public static Path dateDir(Path root, LocalDate date) {
        return root.resolve(date.format(DATE_FORMAT));
    }

    /**
     * Build a real group directory for the supplied date.
     */
    public static Path groupDir(Path root, LocalDate date, String group) {
        validateGroup(group);
        return dateDir(root, date).resolve(group);
    }

    /**
     * Build the canonical real path for a virtual resource path and ensure it stays under
     * {@code root} without ever traversing a symbolic link.
     */
    public static ResolvedResource resolve(Path root, String virtualPath) {
        validateRoot(root);
        if (virtualPath == null) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        String normalized = virtualPath.replace('\\', '/');
        if (!normalized.startsWith(VIRTUAL_PREFIX)) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        String tail = normalized.substring(VIRTUAL_PREFIX.length());
        if (tail.isEmpty() || tail.endsWith("/")) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        String[] parts = tail.split("/");
        if (parts.length < 3) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        LocalDate date = parseDate(parts[0]);
        String group = validateGroup(parts[1]);
        for (int i = 2; i < parts.length; i++) {
            validateRelativeSegment(parts[i]);
        }
        Path real = root.resolve(date.format(DATE_FORMAT)).resolve(group);
        for (int i = 2; i < parts.length; i++) {
            real = real.resolve(parts[i]);
        }
        return new ResolvedResource(root, date, group, real, joinRelative(parts, 2));
    }

    /**
     * Build the canonical real path under a known safe date and group. Returns the joined
     * relative path for convenient listing.
     */
    public static ResolvedResource resolveUnderGroup(Path root, LocalDate date, String group, String relativePath) {
        validateRoot(root);
        validateDate(date);
        group = validateGroup(group);
        Path groupReal = groupDir(root, date, group);
        if (relativePath == null || relativePath.isEmpty()) {
            return new ResolvedResource(root, date, group, groupReal, "");
        }
        String normalized = relativePath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty() || normalized.endsWith("/")) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        String[] parts = normalized.split("/");
        for (String part : parts) {
            validateRelativeSegment(part);
        }
        Path real = groupReal;
        for (String part : parts) {
            real = real.resolve(part);
        }
        return new ResolvedResource(root, date, group, real, joinRelative(parts, 0));
    }

    /**
     * Determine the resource source (UPLOAD or EXECUTION) from a group name.
     */
    public static HubResourceSource detectSource(String group) {
        if (group == null) {
            return null;
        }
        if (group.startsWith("upload-")) {
            return HubResourceSource.UPLOAD;
        }
        if (group.startsWith("execution-")) {
            return HubResourceSource.EXECUTION;
        }
        return null;
    }

    /**
     * Build a fresh upload group identifier. The result is always prefixed with {@code upload-}.
     */
    public static String newUploadGroup() {
        return "upload-" + UUID.randomUUID();
    }

    /**
     * Build the execution group identifier for the supplied execution id.
     */
    public static String executionGroup(long executionId) {
        if (executionId <= 0L) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        return "execution-" + executionId;
    }

    /**
     * Parse and validate a {@code yyyy-MM-dd} date string.
     */
    public static LocalDate parseDate(String value) {
        if (value == null) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        try {
            return LocalDate.parse(value, DATE_FORMAT);
        } catch (DateTimeParseException ex) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
    }

    /**
     * Validate and convert an arbitrary string into a safe filesystem segment for an upload
     * file name. The returned name is deduped against siblings by the caller.
     */
    public static String sanitizeUploadFileName(String original) {
        String name = original == null ? "" : original.trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return "file";
        }
        // Strip Windows drive letter prefix.
        if (name.length() >= 2 && Character.isLetter(name.charAt(0)) && name.charAt(1) == ':') {
            name = name.substring(2);
        }
        if (name.startsWith("/") || name.startsWith("\\")) {
            name = name.replaceFirst("^[\\\\/]+", "");
        }
        // Reject any segment separators or NUL inside the candidate name.
        if (UPLOAD_FILE_NAME_INVALID.matcher(name).find()) {
            name = UPLOAD_FILE_NAME_INVALID.matcher(name).replaceAll("_");
        }
        // Drop any remaining navigation tokens and trailing dots/spaces.
        name = name.replace("..", "_");
        while (name.endsWith(".") || name.endsWith(" ")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.isEmpty()) {
            return "file";
        }
        if (name.length() > 200) {
            name = name.substring(0, 200);
        }
        return name;
    }

    /**
     * Best-effort unique file name inside a target directory. Designed to be deterministic:
     * {@code name.txt -> name.txt}, {@code name (2).txt} for collisions.
     */
    public static String resolveFileNameConflict(Path groupDir, String desired) {
        Path candidate = groupDir.resolve(desired);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return desired;
        }
        int dot = desired.lastIndexOf('.');
        String base;
        String ext;
        if (dot > 0 && dot < desired.length() - 1) {
            base = desired.substring(0, dot);
            ext = desired.substring(dot);
        } else {
            base = desired;
            ext = "";
        }
        for (int i = 2; i < 10_000; i++) {
            String candidateName = base + " (" + i + ")" + ext;
            if (!Files.exists(groupDir.resolve(candidateName), LinkOption.NOFOLLOW_LINKS)) {
                return candidateName;
            }
        }
        return desired + "-" + UUID.randomUUID();
    }

    /**
     * Walk an existing path and reject symbolic links anywhere along the chain.
     * Targets that do not yet exist are returned unchanged so callers may create them safely.
     */
    public static Path ensureNoSymlink(Path root, Path target) throws IOException {
        if (!target.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        Path absolute = target.toAbsolutePath().normalize();
        Path current = root.toAbsolutePath().normalize();
        for (Path segment : absolute) {
            if (current.equals(absolute)) {
                break;
            }
            current = current.resolve(segment.toString());
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
                }
            }
        }
        return target;
    }

    /**
     * Recursively delete an existing directory tree without following symbolic links.
     * Plain files are removed in place; symlinks are unlinked without traversing.
     */
    public static void deleteRecursivelyNoFollow(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root)) {
            Files.delete(root);
            return;
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(root);
            return;
        }
        try (var stream = Files.list(root)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                deleteRecursivelyNoFollow(child);
            }
        }
        Files.delete(root);
    }

    /**
     * Attempt to delete empty directories walking upward until a non-empty directory or root
     * is encountered. Never follows symlinks.
     */
    public static void pruneEmptyAncestors(Path root, Path starting) throws IOException {
        Path current = starting == null ? null : starting.toAbsolutePath().normalize();
        Path rootAbs = root.toAbsolutePath().normalize();
        while (current != null && current.startsWith(rootAbs) && !current.equals(rootAbs)) {
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                current = current.getParent();
                continue;
            }
            if (Files.isSymbolicLink(current)) {
                return;
            }
            boolean empty;
            try (var stream = Files.list(current)) {
                empty = !stream.iterator().hasNext();
            } catch (IOException ex) {
                return;
            }
            if (!empty) {
                return;
            }
            Files.delete(current);
            current = current.getParent();
        }
    }

    private static void validateRoot(Path root) {
        requireAbsolute(root);
    }

    private static Path requireAbsolute(Path root) {
        Path abs = root.toAbsolutePath().normalize();
        if (abs == null || abs.toString().isEmpty()) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        return abs;
    }

    private static void validateDate(LocalDate date) {
        if (date == null) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
    }

    private static String validateGroup(String group) {
        if (group == null || group.isBlank()) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        if (!GROUP_PATTERN.matcher(group).matches()) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        String lower = group.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("upload-") || lower.startsWith("execution-"))) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        return group;
    }

    private static void validateRelativeSegment(String segment) {
        if (segment == null || segment.isEmpty()) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        if (".".equals(segment) || "..".equals(segment)) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        if (!RELATIVE_SEGMENT_PATTERN.matcher(segment).matches()) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
    }

    private static String joinRelative(String[] parts, int offset) {
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < parts.length; i++) {
            if (i > offset) {
                sb.append('/');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /**
     * Output of a successful path resolution. Carries both the real path and metadata needed
     * by callers to format DTOs without re-parsing the virtual path.
     */
    public static final class ResolvedResource {

        private final Path root;
        private final LocalDate date;
        private final String group;
        private final Path realPath;
        private final String relativePath;

        ResolvedResource(Path root, LocalDate date, String group, Path realPath, String relativePath) {
            this.root = root;
            this.date = date;
            this.group = group;
            this.realPath = realPath;
            this.relativePath = relativePath;
        }

        public Path getRoot() {
            return root;
        }

        public LocalDate getDate() {
            return date;
        }

        public String getGroup() {
            return group;
        }

        public Path getRealPath() {
            return realPath;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public String virtualPath() {
            return VIRTUAL_PREFIX + date.format(DATE_FORMAT) + "/" + group + "/" + relativePath;
        }

        public String resourcePath() {
            return VIRTUAL_PREFIX + date.format(DATE_FORMAT) + "/" + group + "/" + (relativePath.isEmpty() ? "" : relativePath);
        }
    }

}
