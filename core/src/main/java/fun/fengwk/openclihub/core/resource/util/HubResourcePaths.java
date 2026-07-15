package fun.fengwk.openclihub.core.resource.util;

import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.resource.HubResourceSource;
import fun.fengwk.openclihub.share.util.HubIds;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
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

    private static final Pattern GROUP_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._\\-]{0,127}");

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private HubResourcePaths() {
    }

    /**
     * Build the real root directory for the resource center from {@link OpenCliHubProperties}.
     * Blank or null {@code rootDir} fails fast so deployment misconfiguration cannot silently
     * fall back to a default that may escape the data volume.
     */
    public static Path resourceRoot(OpenCliHubProperties properties) {
        if (properties == null || properties.getResource() == null) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        String root = properties.getResource().getRootDir();
        if (root == null || root.trim().isEmpty()) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        return requireAbsolute(Path.of(root).normalize());
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
        Path rootAbs = requireAbsolute(root);
        if (virtualPath == null) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        if (!virtualPath.startsWith(VIRTUAL_PREFIX)) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        String tail = virtualPath.substring(VIRTUAL_PREFIX.length());
        if (tail.isEmpty() || tail.endsWith("/")) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        String[] parts = tail.split("/", -1);
        if (parts.length < 3) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        LocalDate date = parseDate(parts[0]);
        String group = validateGroup(parts[1]);
        for (int i = 2; i < parts.length; i++) {
            validateRelativeSegment(parts[i]);
        }
        Path real = rootAbs.resolve(date.format(DATE_FORMAT)).resolve(group);
        for (int i = 2; i < parts.length; i++) {
            real = real.resolve(parts[i]);
        }
        ensureNoSymlinkUnchecked(rootAbs, real);
        return new ResolvedResource(rootAbs, date, group, real, joinRelative(parts, 2));
    }

    /**
     * Build the canonical real path under a known safe date and group. Returns the joined
     * relative path for convenient listing.
     */
    public static ResolvedResource resolveUnderGroup(Path root, LocalDate date, String group, String relativePath) {
        Path rootAbs = requireAbsolute(root);
        validateDate(date);
        group = validateGroup(group);
        Path groupReal = groupDir(rootAbs, date, group);
        if (relativePath == null || relativePath.isEmpty()) {
            ensureNoSymlinkUnchecked(rootAbs, groupReal);
            return new ResolvedResource(rootAbs, date, group, groupReal, "");
        }
        if (relativePath.endsWith("/")) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        String[] parts = relativePath.split("/", -1);
        for (String part : parts) {
            validateRelativeSegment(part);
        }
        Path real = groupReal;
        for (String part : parts) {
            real = real.resolve(part);
        }
        ensureNoSymlinkUnchecked(rootAbs, real);
        return new ResolvedResource(rootAbs, date, group, real, joinRelative(parts, 0));
    }

    /**
     * Encode a resource virtual path one segment at a time using the same safe character set
     * as JavaScript's {@code encodeURIComponent}. Path separators between nested relative
     * segments remain structural and are never encoded as part of a segment.
     */
    public static String encodedVirtualPath(LocalDate date, String group, String relativePath) {
        validateDate(date);
        group = validateGroup(group);
        if (relativePath == null || relativePath.isEmpty() || relativePath.endsWith("/")) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        String[] parts = relativePath.split("/", -1);
        StringBuilder encoded = new StringBuilder(VIRTUAL_PREFIX)
            .append(encodePathSegment(date.format(DATE_FORMAT)))
            .append('/')
            .append(encodePathSegment(group));
        for (String part : parts) {
            validateRelativeSegment(part);
            encoded.append('/').append(encodePathSegment(part));
        }
        return encoded.toString();
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
    public static String executionGroup(String executionId) {
        if (!HubIds.isSupported(executionId)) {
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
        // Replace path separators and control characters that the virtual-path contract
        // deliberately refuses to expose after upload.
        name = sanitizeRelativeSegmentCharacters(name);
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
     * Best-effort unique file name inside a target directory using atomic {@code CREATE_NEW}
     * to claim a slot, falling back to {@code name (2)}, {@code name (3)}, ... when the
     * candidate already exists. The placeholder created by {@code CREATE_NEW} is left on
     * disk; callers are expected to either replace it via {@link Files#move} (atomic
     * preferred) or to remove it on failure.
     * <p>
     * The implementation is safe to invoke from concurrent threads because it relies on
     * {@link java.nio.file.StandardOpenOption#CREATE_NEW} for the existence check rather
     * than a {@code Files.exists(...)} read followed by a separate create.
     */
    public static String reserveFileName(Path groupDir, String desired) throws IOException {
        Objects.requireNonNull(groupDir, "groupDir");
        String name = sanitizeUploadFileName(desired);
        String direct = tryReserve(groupDir, name);
        if (direct != null) {
            return direct;
        }
        int dot = name.lastIndexOf('.');
        String base;
        String ext;
        if (dot > 0 && dot < name.length() - 1) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        } else {
            base = name;
            ext = "";
        }
        for (int i = 2; i < 10_000; i++) {
            String candidateName = base + " (" + i + ")" + ext;
            String reserved = tryReserve(groupDir, candidateName);
            if (reserved != null) {
                return reserved;
            }
        }
        // Extremely unlikely (slot exhaustion) — fall back to a UUID-suffixed placeholder.
        String fallback = name + "-" + UUID.randomUUID();
        String reserved = tryReserve(groupDir, fallback);
        if (reserved != null) {
            return reserved;
        }
        throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
    }

    private static String tryReserve(Path groupDir, String candidate) throws IOException {
        Path target = groupDir.resolve(candidate);
        ensureNoSymlink(groupDir, target);
        try {
            // CREATE_NEW is atomic on POSIX: the channel creation either succeeds (and the
            // file now exists) or throws FileAlreadyExistsException. This is the canonical
            // primitive for "create exactly once if absent".
            try (java.nio.channels.SeekableByteChannel ch = Files.newByteChannel(target,
                java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE)) {
                return candidate;
            }
        } catch (FileAlreadyExistsException ex) {
            return null;
        }
    }

    /**
     * Legacy non-atomic conflict resolver kept for unit tests that exercise the
     * sanitize-only path. New code paths must use {@link #reserveFileName(Path, String)}.
     */
    static String resolveFileNameConflict(Path groupDir, String desired) {
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
     * Non-existent suffixes are allowed so callers can create them after validation; the
     * normalized absolute target is returned. This check does not eliminate same-privilege
     * filesystem replacement races between validation and a later operation.
     */
    public static Path ensureNoSymlink(Path root, Path target) throws IOException {
        Path rootAbs = requireAbsolute(root);
        Path targetAbs = requireAbsolute(target);
        if (!targetAbs.startsWith(rootAbs)) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        Path current = rootAbs;
        rejectSymlink(current);
        for (Path segment : rootAbs.relativize(targetAbs)) {
            current = current.resolve(segment);
            rejectSymlink(current);
        }
        return targetAbs;
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

    private static Path requireAbsolute(Path root) {
        if (root == null) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
        Path abs = root.toAbsolutePath().normalize();
        if (abs.toString().isEmpty()) {
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
        for (int i = 0; i < segment.length(); i++) {
            char ch = segment.charAt(i);
            if (ch == '/' || ch == '\\' || ch == '\u0000' || Character.isISOControl(ch)) {
                throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
            }
        }
    }

    private static String sanitizeRelativeSegmentCharacters(String value) {
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '/' || ch == '\\' || ch == '\u0000' || Character.isISOControl(ch)) {
                sanitized.append('_');
            } else {
                sanitized.append(ch);
            }
        }
        return sanitized.toString();
    }

    private static void rejectSymlink(Path path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable();
        }
    }

    private static void ensureNoSymlinkUnchecked(Path root, Path target) {
        try {
            ensureNoSymlink(root, target);
        } catch (IOException ex) {
            throw HubErrorCodes.RESOURCE_PATH_INVALID.asThrowable(ex);
        }
    }

    private static String encodePathSegment(String segment) {
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte b : bytes) {
            int value = b & 0xff;
            if (isEncodeURIComponentSafe(value)) {
                encoded.append((char) value);
            } else {
                encoded.append('%')
                    .append(HEX[value >>> 4])
                    .append(HEX[value & 0x0f]);
            }
        }
        return encoded.toString();
    }

    private static boolean isEncodeURIComponentSafe(int value) {
        return value >= 'A' && value <= 'Z'
            || value >= 'a' && value <= 'z'
            || value >= '0' && value <= '9'
            || value == '-' || value == '_' || value == '.' || value == '!'
            || value == '~' || value == '*' || value == '\'' || value == '(' || value == ')';
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
