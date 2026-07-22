package fun.fengwk.openclihub.core.instance.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChromeProfileFileAccessBootstrapTest {

    private static final String TEST_EXTENSION_ID = "abcdefghijklmnopabcdefghijklmnop";

    private Path tempDir;
    private Path chromeDir;
    private Path buildInfoPath;
    private ChromeProfileFileAccessBootstrap bootstrap;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("chrome-file-access-test");
        chromeDir = Files.createDirectory(tempDir.resolve("chrome"));
        buildInfoPath = tempDir.resolve("opencli").resolve("crx").resolve("build-info.json");
        Files.createDirectories(buildInfoPath.getParent());
        Files.writeString(buildInfoPath, "{\"extensionId\":\"" + TEST_EXTENSION_ID + "\"}");
        objectMapper = new ObjectMapper();
        bootstrap = new ChromeProfileFileAccessBootstrap(objectMapper, buildInfoPath);
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(tempDir);
    }

    @Test
    void shouldCreatePreferencesWhenMissing() throws IOException {
        bootstrap.bootstrap(chromeDir);

        JsonNode preferences = readPreferences();
        assertThat(preferences.at("/extensions/settings/"
            + TEST_EXTENSION_ID + "/file_access").asBoolean())
            .isTrue();
    }

    @Test
    void shouldPreserveExistingDataAndBeIdempotent() throws IOException {
        Path defaultDir = Files.createDirectory(chromeDir.resolve("Default"));
        Path preferences = defaultDir.resolve("Preferences");
        Files.writeString(preferences, "{\"profile\":{\"name\":\"kept\"},"
            + "\"extensions\":{\"other\":{\"value\":7}}}");

        bootstrap.bootstrap(chromeDir);
        bootstrap.bootstrap(chromeDir);

        JsonNode actual = readPreferences();
        assertThat(actual.at("/profile/name").asText()).isEqualTo("kept");
        assertThat(actual.at("/extensions/other/value").asInt()).isEqualTo(7);
        assertThat(actual.at("/extensions/settings/"
            + TEST_EXTENSION_ID + "/file_access").asBoolean())
            .isTrue();
        try (var files = Files.list(defaultDir)) {
            assertThat(files.map(Path::getFileName).map(Path::toString)
                .filter(name -> name.startsWith(".Preferences.")).toList()).isEmpty();
        }
    }

    @Test
    void shouldRejectBuildInfoSymlink() throws IOException {
        Path target = tempDir.resolve("build-info-target.json");
        Files.writeString(target, "{\"extensionId\":\"" + TEST_EXTENSION_ID + "\"}");
        Files.delete(buildInfoPath);
        Files.createSymbolicLink(buildInfoPath, target);

        assertThatThrownBy(() -> bootstrap.bootstrap(chromeDir))
            .isInstanceOf(IOException.class);
        assertThat(Files.exists(chromeDir.resolve("Default"))).isFalse();
    }

    @Test
    void shouldRejectMissingInvalidJsonAndInvalidExtensionId() throws IOException {
        Files.delete(buildInfoPath);
        assertThatThrownBy(() -> bootstrap.bootstrap(chromeDir))
            .isInstanceOf(IOException.class);

        Files.createDirectory(buildInfoPath);
        assertThatThrownBy(() -> bootstrap.bootstrap(chromeDir))
            .isInstanceOf(IOException.class);
        Files.delete(buildInfoPath);

        Files.writeString(buildInfoPath, "{not-json");
        assertThatThrownBy(() -> bootstrap.bootstrap(chromeDir))
            .isInstanceOf(IOException.class);

        Files.writeString(buildInfoPath, "[]");
        assertThatThrownBy(() -> bootstrap.bootstrap(chromeDir))
            .isInstanceOf(IOException.class);

        Files.writeString(buildInfoPath, "{}");
        assertThatThrownBy(() -> bootstrap.bootstrap(chromeDir))
            .isInstanceOf(IOException.class);

        Files.writeString(buildInfoPath, "{\"extensionId\":\"abcdefghijklmnopabcdefghijklmnoq\"}");
        assertThatThrownBy(() -> bootstrap.bootstrap(chromeDir))
            .isInstanceOf(IOException.class);
        assertThat(Files.exists(chromeDir.resolve("Default"))).isFalse();
    }

    @Test
    void shouldRejectSymlinkedChromeDefaultAndPreferences() throws IOException {
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path chromeLink = tempDir.resolve("chrome-link");
        Files.createSymbolicLink(chromeLink, chromeDir);
        assertThatThrownBy(() -> bootstrap.bootstrap(chromeLink))
            .isInstanceOf(IOException.class);

        Path defaultLink = chromeDir.resolve("Default");
        Files.createSymbolicLink(defaultLink, outside);
        assertThatThrownBy(() -> bootstrap.bootstrap(chromeDir))
            .isInstanceOf(IOException.class);
        Files.delete(defaultLink);

        Path defaultDir = Files.createDirectory(defaultLink);
        Path preferencesTarget = Files.writeString(tempDir.resolve("preferences-target"), "{}");
        Files.createSymbolicLink(defaultDir.resolve("Preferences"), preferencesTarget);
        assertThatThrownBy(() -> bootstrap.bootstrap(chromeDir))
            .isInstanceOf(IOException.class);
    }

    @Test
    void shouldRejectDirectoryTypeAnomalies() throws IOException {
        Path chromeFile = tempDir.resolve("chrome-file");
        Files.writeString(chromeFile, "not a directory");
        assertThatThrownBy(() -> bootstrap.bootstrap(chromeFile))
            .isInstanceOf(IOException.class);

        Path defaultPath = chromeDir.resolve("Default");
        Files.writeString(defaultPath, "not a directory");
        assertThatThrownBy(() -> bootstrap.bootstrap(chromeDir))
            .isInstanceOf(IOException.class);
        Files.delete(defaultPath);

        Path defaultDir = Files.createDirectory(defaultPath);
        Files.createDirectory(defaultDir.resolve("Preferences"));
        assertThatThrownBy(() -> bootstrap.bootstrap(chromeDir))
            .isInstanceOf(IOException.class);
    }

    @Test
    void shouldRejectInvalidOrEmptyPreferencesJson() throws IOException {
        Path defaultDir = Files.createDirectory(chromeDir.resolve("Default"));
        Path preferences = defaultDir.resolve("Preferences");
        Files.writeString(preferences, " ");
        assertThatThrownBy(() -> bootstrap.bootstrap(chromeDir))
            .isInstanceOf(IOException.class);

        Files.writeString(preferences, "{not-json");
        assertThatThrownBy(() -> bootstrap.bootstrap(chromeDir))
            .isInstanceOf(IOException.class);
    }

    private JsonNode readPreferences() throws IOException {
        return objectMapper.readTree(Files.readString(
            chromeDir.resolve("Default").resolve("Preferences")));
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

}
