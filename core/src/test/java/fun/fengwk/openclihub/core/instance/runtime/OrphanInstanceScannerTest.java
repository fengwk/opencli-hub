package fun.fengwk.openclihub.core.instance.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.instance.runtime.test.InMemoryHubInstanceService;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link OrphanInstanceScanner} which reconciles on-disk orphan directories against the
 * persisted instance table.
 */
class OrphanInstanceScannerTest {

    private Path dataDir;
    private InMemoryHubInstanceService service;
    private OpenCliHubProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = Files.createTempDirectory("m4-orphan-test");
        service = new InMemoryHubInstanceService();
        properties = new OpenCliHubProperties();
        properties.setDataDir(dataDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(dataDir);
    }

    @Test
    void shouldRemoveCreatingOrphanWithoutDbRow() throws IOException {
        Path root = Files.createDirectories(dataDir.resolve("instances/9999/chrome"));
        Files.createFile(dataDir.resolve("instances/9999/.creating"));

        OrphanInstanceScanner.Result result = new OrphanInstanceScanner(properties, service).scan();

        assertThat(result.creatingOrphanDeleted).isEqualTo(1);
        assertThat(Files.exists(root)).isFalse();
    }

    @Test
    void shouldRemoveCreatingMarkerWhenDbRowExists() throws IOException {
        long id = service.reserveId();
        var inst = new fun.fengwk.openclihub.core.instance.service.model.HubInstance();
        inst.setId(id);
        inst.setCode("bilibili-create");
        inst.setDisplayName("x");
        inst.setMaxPending(5);
        inst.setWebsites(java.util.List.of("bilibili"));
        inst.setState(fun.fengwk.openclihub.share.model.instance.HubInstanceState.STOPPED);
        service.create(inst);

        Path root = Files.createDirectories(dataDir.resolve("instances/" + id));
        Files.createFile(root.resolve(".creating"));
        Files.writeString(root.resolve("Cookies"), "kept");

        OrphanInstanceScanner.Result result = new OrphanInstanceScanner(properties, service).scan();

        assertThat(result.creatingMarkerRemoved).isEqualTo(1);
        assertThat(Files.exists(root.resolve(".creating"))).isFalse();
        assertThat(Files.exists(root.resolve("Cookies"))).isTrue();
    }

    @Test
    void shouldRemoveNumericOrphanDirectory() throws IOException {
        Files.createDirectories(dataDir.resolve("instances/7777/chrome"));
        Files.writeString(dataDir.resolve("instances/7777/chrome/Preferences"), "{}");

        OrphanInstanceScanner.Result result = new OrphanInstanceScanner(properties, service).scan();

        assertThat(result.numericOrphanDeleted).isEqualTo(1);
        assertThat(Files.exists(dataDir.resolve("instances/7777"))).isFalse();
    }

    @Test
    void shouldProtectNonNumericDirectory() throws IOException {
        Files.createDirectories(dataDir.resolve("instances/manual-tmp"));

        OrphanInstanceScanner.Result result = new OrphanInstanceScanner(properties, service).scan();

        assertThat(result.nonNumericProtected).isEqualTo(1);
        assertThat(Files.exists(dataDir.resolve("instances/manual-tmp"))).isTrue();
    }

    @Test
    void shouldReturnZerosWhenInstancesRootMissing() {
        OrphanInstanceScanner.Result result = new OrphanInstanceScanner(properties, service).scan();
        assertThat(result.total()).isZero();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            var sorted = stream.sorted((a, b) -> b.compareTo(a)).toList();
            for (Path p : sorted) {
                Files.deleteIfExists(p);
            }
        }
    }

}
