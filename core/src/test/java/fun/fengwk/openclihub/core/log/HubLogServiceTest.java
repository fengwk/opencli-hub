package fun.fengwk.openclihub.core.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fun.fengwk.convention4j.api.code.ThrowableConventionErrorCode;
import fun.fengwk.openclihub.core.instance.runtime.HubInstanceDirectoryLayout;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.log.HubLogContentDTO;
import fun.fengwk.openclihub.share.model.log.HubLogSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests fixed-source log tailing and streaming against real temporary files. */
class HubLogServiceTest {

    @TempDir
    Path dataDir;
    @TempDir
    Path loggingDir;

    private HubLogService logService;

    @BeforeEach
    void setUp() {
        OpenCliHubProperties properties = new OpenCliHubProperties();
        properties.setDataDir(dataDir.toString());
        logService = new HubLogService(properties, loggingDir.toString());
    }

    /** A large prefix is skipped so only the chronologically ordered tail is returned. */
    @Test
    void shouldTailLargeSystemLogFromEndInChronologicalOrder() throws IOException {
        String prefix = IntStream.range(0, 20_000)
            .mapToObj(index -> "old-" + index + "\n")
            .collect(java.util.stream.Collectors.joining());
        String expected = "new-first\nnew-second\nnew-third\n";
        Path systemLog = loggingDir.resolve(HubLogService.SYSTEM_LOG_FILE_NAME);
        Files.writeString(systemLog, prefix + expected);

        HubLogContentDTO content = logService.tailSystem(3);

        assertThat(content.getSource()).isEqualTo(HubLogSource.SYSTEM);
        assertThat(content.getInstanceId()).isNull();
        assertThat(content.getContent()).isEqualTo(expected);
        assertThat(content.isTruncated()).isTrue();
        assertThat(content.getFileSize()).isGreaterThan(expected.length());
        assertThat(content.getModifiedAt()).isNotNull();
    }

    /** A single oversized line is capped so tail responses cannot allocate the whole file. */
    @Test
    void shouldCapTailBytesForOversizedLine() throws IOException {
        Path systemLog = loggingDir.resolve(HubLogService.SYSTEM_LOG_FILE_NAME);
        Files.writeString(systemLog, "x".repeat(HubLogService.MAX_TAIL_BYTES + 1024));

        HubLogContentDTO content = logService.tailSystem(1);

        assertThat(content.getContent().getBytes(StandardCharsets.UTF_8))
            .hasSize(HubLogService.MAX_TAIL_BYTES);
        assertThat(content.isTruncated()).isTrue();
    }

    /** Line counts outside the documented positive maximum fail before any file read. */
    @Test
    void shouldRejectOutOfRangeLineCounts() {
        assertThatThrownBy(() -> logService.tailSystem(0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> logService.tailSystem(HubLogService.MAX_TAIL_LINES + 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** Instance source selection maps only fixed enum values to the existing layout paths. */
    @Test
    void shouldUseOnlyFixedInstanceLogPaths() throws IOException {
        Path chromeLog = HubInstanceDirectoryLayout.chromeLog(dataDir.toString(), 41L);
        Files.createDirectories(chromeLog.getParent());
        Files.writeString(chromeLog, "chrome-line\n");
        Path outside = dataDir.resolve("secret.log");
        Files.writeString(outside, "secret\n");

        HubLogContentDTO content = logService.tailInstance(41L, HubLogSource.CHROME, 1);

        assertThat(content.getSource()).isEqualTo(HubLogSource.CHROME);
        assertThat(content.getContent()).isEqualTo("chrome-line\n");
        assertThatThrownBy(() -> HubLogService.parseInstanceSource("../../secret.log"))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(error -> assertThat(((ThrowableConventionErrorCode) error).getCode())
                .isEqualTo(HubErrorCodes.INSTANCE_LOG_SOURCE_INVALID.getCode()));
        assertThatThrownBy(() -> logService.tailInstance(41L, HubLogSource.SYSTEM, 1))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(error -> assertThat(((ThrowableConventionErrorCode) error).getCode())
                .isEqualTo(HubErrorCodes.INSTANCE_LOG_SOURCE_INVALID.getCode()));
    }

    /** Fixed downloads expose exact bytes and missing managed files retain the Hub 404 code. */
    @Test
    void shouldStreamFixedLogAndMapMissingFile() throws IOException {
        Path xvfbLog = HubInstanceDirectoryLayout.xvfbLog(dataDir.toString(), 42L);
        Files.createDirectories(xvfbLog.getParent());
        Files.writeString(xvfbLog, "xvfb-output\n");

        try (HubLogStream stream = logService.openInstanceDownload(42L, HubLogSource.XVFB)) {
            assertThat(stream.getFileName()).isEqualTo(HubInstanceDirectoryLayout.LOG_XVFB);
            assertThat(new String(stream.getInputStream().readAllBytes())).isEqualTo("xvfb-output\n");
        }
        assertThatThrownBy(() -> logService.tailInstance(99L, HubLogSource.OPENBOX, 1))
            .isInstanceOf(ThrowableConventionErrorCode.class)
            .satisfies(error -> assertThat(((ThrowableConventionErrorCode) error).getCode())
                .isEqualTo(HubErrorCodes.LOG_FILE_NOT_FOUND.getCode()));
    }

}
