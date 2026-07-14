package fun.fengwk.openclihub.core.instance.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import fun.fengwk.openclihub.core.instance.runtime.test.InMemoryHubInstanceService;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests safe reconciliation of on-disk instance directories. */
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
    void shouldRemoveCreatingOrphanForLegacyNumericId() throws IOException {
        Path root = Files.createDirectories(dataDir.resolve("instances/9999/chrome"));
        Files.createFile(dataDir.resolve("instances/9999/.creating"));

        OrphanInstanceScanner.Result result = scanner().scan();

        assertThat(result.creatingOrphanDeleted).isEqualTo(1);
        assertThat(Files.exists(root)).isFalse();
    }

    @Test
    void shouldRemoveCreatingMarkerWhenUuidDbRowExists() throws IOException {
        String id = service.reserveId();
        HubInstance instance = persisted(id);
        service.create(instance);

        Path root = Files.createDirectories(dataDir.resolve("instances/" + id));
        Files.createFile(root.resolve(".creating"));
        Files.writeString(root.resolve("Cookies"), "kept");

        OrphanInstanceScanner.Result result = scanner().scan();

        assertThat(result.creatingMarkerRemoved).isEqualTo(1);
        assertThat(Files.exists(root.resolve(".creating"))).isFalse();
        assertThat(Files.exists(root.resolve("Cookies"))).isTrue();
    }

    @Test
    void shouldRemoveUuidAndLegacyNumericOrphans() throws IOException {
        String uuid = UUID.randomUUID().toString();
        Files.createDirectories(dataDir.resolve("instances/7777/chrome"));
        Files.createDirectories(dataDir.resolve("instances/" + uuid + "/chrome"));

        OrphanInstanceScanner.Result result = scanner().scan();

        assertThat(result.managedOrphanDeleted).isEqualTo(2);
        assertThat(Files.exists(dataDir.resolve("instances/7777"))).isFalse();
        assertThat(Files.exists(dataDir.resolve("instances/" + uuid))).isFalse();
    }

    @Test
    void shouldProtectUnmanagedDirectoryEvenWithCreatingMarker() throws IOException {
        Path manual = Files.createDirectories(dataDir.resolve("instances/manual-tmp"));
        Files.createFile(manual.resolve(".creating"));
        Path malformedUuid = Files.createDirectories(
            dataDir.resolve("instances/123e4567-e89b-12d3-a456-not-a-uuid"));
        Files.createFile(malformedUuid.resolve(".creating"));

        OrphanInstanceScanner.Result result = scanner().scan();

        assertThat(result.unsafeNameProtected).isEqualTo(2);
        assertThat(manual).exists();
        assertThat(malformedUuid).exists();
    }

    @Test
    void shouldProtectNumericNamesThatWereNeverValidLegacyLongIds() throws IOException {
        Path zero = Files.createDirectories(dataDir.resolve("instances/0"));
        Path leadingZero = Files.createDirectories(dataDir.resolve("instances/00042"));
        Path outOfRange = Files.createDirectories(
            dataDir.resolve("instances/999999999999999999999999999999"));

        OrphanInstanceScanner.Result result = scanner().scan();

        assertThat(result.unsafeNameProtected).isEqualTo(3);
        assertThat(zero).exists();
        assertThat(leadingZero).exists();
        assertThat(outOfRange).exists();
    }

    @Test
    void shouldReturnZerosWhenInstancesRootMissing() {
        assertThat(scanner().scan().total()).isZero();
    }

    private OrphanInstanceScanner scanner() {
        return new OrphanInstanceScanner(properties, service);
    }

    private static HubInstance persisted(String id) {
        HubInstance instance = new HubInstance();
        instance.setId(id);
        instance.setCode("bilibili-create");
        instance.setDisplayName("x");
        instance.setMaxPending(5);
        instance.setWebsites(List.of("bilibili"));
        instance.setState(HubInstanceState.STOPPED);
        return instance;
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
