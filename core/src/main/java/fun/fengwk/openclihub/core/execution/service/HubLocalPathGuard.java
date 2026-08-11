package fun.fengwk.openclihub.core.execution.service;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.core.resource.util.HubResourcePaths;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Single-responsibility guard for every local-path boundary of an execution.
 *
 * <p>Policy: the only file reference a caller may supply is a {@code /resources/...}
 * virtual path (uploaded resources, or outputs of previous executions). Every other
 * path-shaped argv value — absolute path, Windows drive path, {@code ~} expansion,
 * explicit traversal, a file or directory that already exists relative to the
 * configured OpenCLI workdir, {@code file:} URI, or a virtual path that escapes
 * the root through traversal or a symbolic link — is rejected with
 * {@link HubErrorCodes#OPENCLI_LOCAL_PATH_NOT_ALLOWED}. Values that are not path-shaped
 * (URLs, prompts, dates, flags — including text that merely contains slashes) pass
 * through unchanged.
 *
 * <p>Relative-path resolution (for the workdir existence probe) is anchored at the
 * configured OpenCLI workdir, never at the JVM working directory, because that is the
 * directory the OpenCLI child process actually runs in.
 *
 * <p>As a final defense, {@link #assertFinalArgv(List)} verifies that every absolute path
 * the Hub assembled is contained in the canonical resource root: for paths that exist the
 * fully-resolved real path must stay under the canonical root; for paths not yet created
 * (managed outputs) the normalized target and its nearest existing parent directory are
 * checked, and the chain from the root to the target must not traverse a symbolic link.
 * All containment decisions use {@link Path} semantics ({@link Path#normalize()},
 * {@link Path#toRealPath()}, {@code NOFOLLOW_LINKS} existence and segment-wise
 * {@link Path#startsWith(Path)}), never raw string prefix comparison.
 *
 * @author fengwk
 */
@Component
public class HubLocalPathGuard {

    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[a-zA-Z]:[\\\\/]");
    private static final Pattern URI_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:");

    private final Path resourceRoot;
    private final Path workdir;

    public HubLocalPathGuard(OpenCliHubProperties properties) {
        this.resourceRoot = HubResourcePaths.resourceRoot(properties);
        String configuredWorkdir = properties.getOpencli().getWorkdir();
        this.workdir = configuredWorkdir == null || configuredWorkdir.isBlank()
            ? null : Path.of(configuredWorkdir).toAbsolutePath().normalize();
    }

    /**
     * Classification of a caller-supplied argv value.
     */
    public enum CallerTokenKind {

        /**
         * A valid {@code /resources/...} virtual path; the Hub must resolve, lease and
         * substitute it with the real on-disk path.
         */
        VIRTUAL_RESOURCE,

        /**
         * An ordinary value (prompt, URL, flag); pass it through unchanged.
         */
        ORDINARY_VALUE
    }

    /**
     * Classify one caller argv value. Throws
     * {@code OPENCLI_LOCAL_PATH_NOT_ALLOWED} when the value is a local path reference.
     * Virtual tokens are validated with {@link HubResourcePaths#resolve(Path, String)}
     * (format, traversal and symlink checks); a malformed or escaping virtual token is a
     * forbidden local-path attempt, not a pass-through.
     */
    public CallerTokenKind classifyCallerToken(String token) {
        if (token == null || token.isBlank()) {
            return CallerTokenKind.ORDINARY_VALUE;
        }
        if (isVirtualResourceToken(token)) {
            assertValidVirtualResource(token);
            return CallerTokenKind.VIRTUAL_RESOURCE;
        }
        if (isFileUri(token)) {
            throw notAllowed("file URI is not allowed: " + token);
        }
        if (isWindowsAbsolutePath(token)) {
            throw notAllowed("Windows-style absolute path is not allowed: " + token);
        }
        if (hasUriScheme(token)) {
            // http/https/data/mailto/... values are not local paths.
            return CallerTokenKind.ORDINARY_VALUE;
        }
        if (looksLikeLocalPath(token)) {
            throw notAllowed(
                "Local path references are not allowed; upload the resource first and "
                    + "reference its /resources/... virtual path: " + token);
        }
        return CallerTokenKind.ORDINARY_VALUE;
    }

    /**
     * Final defensive validation of the assembled argv. Every absolute token must be
     * contained in the canonical resource root; Windows-style drive tokens are never
     * legitimate in a Hub-assembled argv. Tokens that are not absolute paths (flags,
     * prompts, URLs) are not checked.
     */
    public void assertFinalArgv(List<String> argv) {
        if (argv == null) {
            return;
        }
        for (String token : argv) {
            if (token == null || token.isBlank()) {
                continue;
            }
            if (isWindowsAbsolutePath(token)) {
                throw notAllowed("Windows-style absolute path is not allowed in the final argv: " + token);
            }
            if (!token.startsWith("/")) {
                continue;
            }
            assertContainedInResourceRoot(Path.of(token));
        }
    }

    /**
     * Verify that an absolute path the Hub assembled stays inside the canonical resource
     * root. The nearest existing ancestor is canonicalized with
     * {@link Path#toRealPath()} (so a symbolic-link escape is detected even when the
     * final target does not exist yet, e.g. a managed output file); the chain from the
     * root to the normalized target must additionally be free of symbolic links
     * (mirrors the resource center's no-symlink policy).
     */
    public void assertContainedInResourceRoot(Path candidate) {
        Objects.requireNonNull(candidate, "candidate");
        Path abs = candidate.toAbsolutePath().normalize();
        Path probe = nearestExistingAncestor(abs);
        if (probe == null) {
            throw notAllowed("Path has no existing ancestor under the resource root: " + candidate);
        }
        Path canonicalRoot = canonicalRoot();
        try {
            if (!probe.toRealPath().startsWith(canonicalRoot)) {
                throw notAllowed("Path escapes the canonical resource root: " + candidate);
            }
        } catch (IOException ex) {
            throw notAllowed("Failed to canonicalize path " + candidate + ": " + ex.getMessage());
        }
        try {
            HubResourcePaths.ensureNoSymlink(resourceRoot, abs);
        } catch (IOException ex) {
            throw notAllowed("Failed to check the path chain for " + candidate + ": " + ex.getMessage());
        } catch (RuntimeException ex) {
            if (isResourcePathInvalid(ex)) {
                throw notAllowed("Path traverses a symbolic link or escapes the resource root: " + candidate);
            }
            throw ex;
        }
    }

    private void assertValidVirtualResource(String token) {
        try {
            HubResourcePaths.resolve(resourceRoot, token);
        } catch (RuntimeException ex) {
            if (isResourcePathInvalid(ex)) {
                throw notAllowed(
                    "Invalid or unsafe /resources/... reference (format, traversal or symlink): " + token);
            }
            throw ex;
        }
    }

    /**
     * Protocol detection: the token claims to be a virtual resource reference. Full
     * validation (including the symlink walk) happens in
     * {@link #assertValidVirtualResource(String)}.
     */
    private static boolean isVirtualResourceToken(String token) {
        String normalized = token.replace('\\', '/');
        return normalized.startsWith(HubResourcePaths.VIRTUAL_PREFIX);
    }

    private boolean looksLikeLocalPath(String token) {
        if (token.startsWith("~")) {
            return true;
        }
        if (token.startsWith("/")) {
            return true;
        }
        if (hasTraversalSegment(token)) {
            return true;
        }
        return existsUnderWorkdir(token);
    }

    /**
     * Detect explicit traversal in a relative value: any standalone {@code .} or
     * {@code ..} segment (e.g. {@code ./x}, {@code ../x}, {@code a/../../b}), split on
     * both slash styles. Ordinary slashed text ({@code 2026/08/12}, {@code foo/bar},
     * full prompts) has no such segment and is not a path candidate by itself.
     */
    private static boolean hasTraversalSegment(String token) {
        String normalized = token.replace('\\', '/');
        for (String segment : normalized.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A non-absolute value is only a path when it already exists relative to the
     * configured OpenCLI workdir (the directory the child process will run in). The
     * probe also covers nested relative paths such as {@code foo/bar} when the file or
     * directory really exists there.
     */
    private boolean existsUnderWorkdir(String token) {
        if (workdir == null) {
            return false;
        }
        return Files.exists(workdir.resolve(token), LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isWindowsAbsolutePath(String token) {
        return WINDOWS_ABSOLUTE_PATH.matcher(token).lookingAt();
    }

    private static boolean isFileUri(String token) {
        return token.regionMatches(true, 0, "file:", 0, 5);
    }

    private static boolean hasUriScheme(String token) {
        return URI_SCHEME.matcher(token).lookingAt();
    }

    private Path canonicalRoot() {
        try {
            // Follows symlinks so a configured root that is itself a link still yields a
            // canonical containment anchor; the per-chain symlink policy is enforced
            // separately by ensureNoSymlink.
            return resourceRoot.toRealPath();
        } catch (IOException ex) {
            // Fresh deployment before the root is created: fall back to the normalized
            // absolute form; containment then relies on the nearest-existing-ancestor
            // real-path check plus the no-symlink chain walk.
            return resourceRoot.toAbsolutePath().normalize();
        }
    }

    private static Path nearestExistingAncestor(Path abs) {
        Path current = abs;
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static boolean isResourcePathInvalid(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ThrowableConventionErrorCode domain
                && HubErrorCodes.RESOURCE_PATH_INVALID.getCode().equals(domain.getCode())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static RuntimeException notAllowed(String message) {
        return HubErrorCodes.OPENCLI_LOCAL_PATH_NOT_ALLOWED.asThrowable(message);
    }
}
