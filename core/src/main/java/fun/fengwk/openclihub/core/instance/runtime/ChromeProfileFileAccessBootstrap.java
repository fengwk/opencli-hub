package fun.fengwk.openclihub.core.instance.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Bootstraps local-file access for the managed OpenCLI Browser Bridge in a stopped Chrome profile.
 *
 * <p>The Preferences file is replaced through a same-directory temporary file so a failed write
 * cannot leave a partially written Chrome profile behind.
 *
 * @author fengwk
 */
public final class ChromeProfileFileAccessBootstrap {

    private static final String DEFAULT_PROFILE = "Default";
    private static final String PREFERENCES = "Preferences";

    private final ObjectMapper objectMapper;
    private final Path buildInfoPath;

    public ChromeProfileFileAccessBootstrap(ObjectMapper objectMapper, Path buildInfoPath) {
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper is required");
        }
        if (buildInfoPath == null) {
            throw new IllegalArgumentException("buildInfoPath is required");
        }
        this.objectMapper = objectMapper;
        this.buildInfoPath = buildInfoPath;
    }

    /**
     * Sets the managed Bridge's {@code newAllowFileAccess} preference without following profile
     * symlinks. Chrome must be stopped by the caller before this method is invoked.
     */
    public void bootstrap(Path chromeDir) throws IOException {
        String extensionId = readExtensionId();
        requireRealDirectory(chromeDir, "Chrome user-data-dir", false);

        Path defaultDir = chromeDir.resolve(DEFAULT_PROFILE);
        requireRealDirectory(defaultDir, "Chrome Default profile", true);
        if (!Files.exists(defaultDir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(defaultDir);
            requireRealDirectory(defaultDir, "Chrome Default profile", false);
        }

        Path preferences = defaultDir.resolve(PREFERENCES);
        if (Files.isSymbolicLink(preferences)) {
            throw new IOException("Chrome Preferences must not be a symbolic link: " + preferences);
        }
        boolean preferencesExists = Files.exists(preferences, LinkOption.NOFOLLOW_LINKS);
        if (preferencesExists && !Files.isRegularFile(preferences, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Chrome Preferences must be a regular file: " + preferences);
        }

        ObjectNode root;
        Set<PosixFilePermission> permissions = null;
        if (preferencesExists) {
            JsonNode parsed = readJsonObject(preferences, "Chrome Preferences");
            root = (ObjectNode) parsed;
            PosixFileAttributeView posixView = Files.getFileAttributeView(
                preferences, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posixView != null) {
                permissions = posixView.readAttributes().permissions();
            }
        } else {
            root = objectMapper.createObjectNode();
        }

        ObjectNode extensions = objectChild(root, "extensions");
        ObjectNode settings = objectChild(extensions, "settings");
        ObjectNode bridge = objectChild(settings, extensionId);
        bridge.put("newAllowFileAccess", true);

        Path temporary = null;
        boolean moved = false;
        try {
            temporary = Files.createTempFile(defaultDir, ".Preferences.", ".tmp");
            if (permissions != null) {
                PosixFileAttributeView tempPosixView = Files.getFileAttributeView(
                    temporary, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (tempPosixView != null) {
                    tempPosixView.setPermissions(permissions);
                }
            }
            byte[] json = objectMapper.writeValueAsBytes(root);
            try (FileChannel channel = FileChannel.open(temporary,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(json);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, preferences,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (!moved && temporary != null) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private String readExtensionId() throws IOException {
        if (Files.isSymbolicLink(buildInfoPath)
            || !Files.exists(buildInfoPath, LinkOption.NOFOLLOW_LINKS)
            || !Files.isRegularFile(buildInfoPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                "OpenCLI build-info must be a real regular file: " + buildInfoPath);
        }
        JsonNode buildInfo = readJsonObject(buildInfoPath, "OpenCLI build-info");
        JsonNode extensionId = buildInfo.get("extensionId");
        if (extensionId == null || !extensionId.isTextual()
            || !extensionId.textValue().matches("[a-p]{32}")) {
            throw new IOException("OpenCLI build-info contains an invalid extensionId: "
                + buildInfoPath);
        }
        return extensionId.textValue();
    }

    private JsonNode readJsonObject(Path path, String description) throws IOException {
        byte[] content = Files.readAllBytes(path);
        try (JsonParser parser = objectMapper.getFactory().createParser(content)) {
            JsonNode parsed = objectMapper.readTree(parser);
            if (parsed == null || !parsed.isObject() || parser.nextToken() != null) {
                throw new IOException(description + " must contain one JSON object: " + path);
            }
            return parsed;
        }
    }

    private ObjectNode objectChild(ObjectNode parent, String name) throws IOException {
        JsonNode child = parent.get(name);
        if (child == null) {
            ObjectNode created = objectMapper.createObjectNode();
            parent.set(name, created);
            return created;
        }
        if (!child.isObject()) {
            throw new IOException("Chrome Preferences field must be an object: " + name);
        }
        return (ObjectNode) child;
    }

    private static void requireRealDirectory(Path path, String description, boolean allowAbsent)
        throws IOException {
        if (path == null) {
            throw new IOException(description + " is required");
        }
        if (Files.isSymbolicLink(path)) {
            throw new IOException(description + " must not be a symbolic link: " + path);
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (allowAbsent) {
                return;
            }
            throw new IOException(description + " does not exist: " + path);
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " must be a directory: " + path);
        }
    }

}
