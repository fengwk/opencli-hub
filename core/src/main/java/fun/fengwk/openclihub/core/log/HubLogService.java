package fun.fengwk.openclihub.core.log;

import fun.fengwk.openclihub.core.instance.runtime.HubInstanceDirectoryLayout;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.log.HubLogContentDTO;
import fun.fengwk.openclihub.share.model.log.HubLogSource;
import fun.fengwk.openclihub.share.util.HubIds;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Provides bounded tails and streams for Hub-owned, fixed log files only.
 *
 * @author fengwk
 */
@Service
public class HubLogService {

    public static final int DEFAULT_TAIL_LINES = 500;
    public static final int MAX_TAIL_LINES = 5000;
    public static final int MAX_TAIL_BYTES = 4 * 1024 * 1024;
    public static final String SYSTEM_LOG_FILE_NAME = "opencli-hub-all.log";

    private static final int TAIL_SCAN_BLOCK_BYTES = 8192;

    private final OpenCliHubProperties properties;
    private final Path loggingDirectory;

    public HubLogService(OpenCliHubProperties properties,
                         @Value("${logging.file.path:${opencli.hub.data-dir}/logs}") String loggingDirectory) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.loggingDirectory = Path.of(Objects.requireNonNull(loggingDirectory, "loggingDirectory"));
    }

    /** Parses a request value without ever treating it as a file-system path. */
    public static HubLogSource parseInstanceSource(String value) {
        if (value == null) {
            throw HubErrorCodes.INSTANCE_LOG_SOURCE_INVALID.asThrowable();
        }
        try {
            HubLogSource source = HubLogSource.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (source == HubLogSource.SYSTEM) {
                throw HubErrorCodes.INSTANCE_LOG_SOURCE_INVALID.asThrowable();
            }
            return source;
        } catch (IllegalArgumentException ex) {
            throw HubErrorCodes.INSTANCE_LOG_SOURCE_INVALID.asThrowable(ex);
        }
    }

    /** Ensures tail reads have a small, documented upper bound. */
    public static void validateLineCount(int lines) {
        if (lines < 1 || lines > MAX_TAIL_LINES) {
            throw new IllegalArgumentException("lines must be between 1 and " + MAX_TAIL_LINES);
        }
    }

    public HubLogContentDTO tailSystem(int lines) {
        return tail(resolveSystem(), lines);
    }

    public HubLogContentDTO tailInstance(String instanceId, HubLogSource source, int lines) {
        return tail(resolveInstance(instanceId, source), lines);
    }

    public HubLogStream openSystemDownload() {
        return openDownload(resolveSystem());
    }

    public HubLogStream openInstanceDownload(String instanceId, HubLogSource source) {
        return openDownload(resolveInstance(instanceId, source));
    }

    private HubLogContentDTO tail(ResolvedLog log, int lines) {
        validateLineCount(lines);
        Path path = requireRegularFile(log.path());
        try {
            long size = Files.size(path);
            Tail tail = readTail(path, size, lines);
            HubLogContentDTO dto = new HubLogContentDTO();
            dto.setSource(log.source());
            dto.setInstanceId(log.instanceId());
            dto.setContent(tail.content());
            dto.setTruncated(tail.truncated());
            dto.setFileSize(size);
            dto.setModifiedAt(LocalDateTime.ofInstant(
                Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant(),
                ZoneId.systemDefault()));
            return dto;
        } catch (IOException ex) {
            throw HubErrorCodes.LOG_FILE_NOT_FOUND.asThrowable(ex);
        }
    }

    private HubLogStream openDownload(ResolvedLog log) {
        Path path = requireRegularFile(log.path());
        try {
            long size = Files.size(path);
            InputStream inputStream = Files.newInputStream(path, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS);
            return new HubLogStream(path.getFileName().toString(), size, inputStream);
        } catch (IOException ex) {
            throw HubErrorCodes.LOG_FILE_NOT_FOUND.asThrowable(ex);
        }
    }

    private ResolvedLog resolveSystem() {
        return new ResolvedLog(loggingDirectory.resolve(SYSTEM_LOG_FILE_NAME), HubLogSource.SYSTEM, null);
    }

    private ResolvedLog resolveInstance(String instanceId, HubLogSource source) {
        if (source == null || source == HubLogSource.SYSTEM) {
            throw HubErrorCodes.INSTANCE_LOG_SOURCE_INVALID.asThrowable();
        }
        if (!HubIds.isSupported(instanceId)) {
            throw HubErrorCodes.LOG_FILE_NOT_FOUND.asThrowable();
        }
        try {
            Path instancesRoot = HubInstanceDirectoryLayout.requireRealInstancesRoot(
                properties.getDataDir());
            Path instanceDir = HubInstanceDirectoryLayout.requireRealInstanceDirectory(
                instancesRoot, instanceId);
            Path logsDir = HubInstanceDirectoryLayout.requireRealInstanceChildDirectory(
                instanceDir, HubInstanceDirectoryLayout.DIR_LOGS);
            Path path = switch (source) {
                case CHROME -> logsDir.resolve(HubInstanceDirectoryLayout.LOG_CHROME);
                case XVFB -> logsDir.resolve(HubInstanceDirectoryLayout.LOG_XVFB);
                case OPENBOX -> logsDir.resolve(HubInstanceDirectoryLayout.LOG_OPENBOX);
                case X11VNC -> logsDir.resolve(HubInstanceDirectoryLayout.LOG_X11VNC);
                case SYSTEM -> throw HubErrorCodes.INSTANCE_LOG_SOURCE_INVALID.asThrowable();
            };
            return new ResolvedLog(path, source, instanceId);
        } catch (IOException ex) {
            throw HubErrorCodes.LOG_FILE_NOT_FOUND.asThrowable(ex);
        }
    }

    private static Path requireRegularFile(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw HubErrorCodes.LOG_FILE_NOT_FOUND.asThrowable();
        }
        return path;
    }

    /**
     * Scans backwards in blocks for line separators and caps the returned tail bytes.
     * The trailing newline does not itself count as an extra line.
     */
    private static Tail readTail(Path path, long fileSize, int lines) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long scanEnd = fileSize;
            if (scanEnd > 0) {
                file.seek(scanEnd - 1);
                if (file.read() == '\n') {
                    scanEnd--;
                }
            }

            long minimumStart = Math.max(0, fileSize - MAX_TAIL_BYTES);
            long cursor = scanEnd;
            long start = minimumStart;
            int separators = 0;
            byte[] block = new byte[TAIL_SCAN_BLOCK_BYTES];
            boolean foundStart = false;
            while (cursor > minimumStart && !foundStart) {
                long blockStart = Math.max(minimumStart, cursor - block.length);
                int blockLength = Math.toIntExact(cursor - blockStart);
                file.seek(blockStart);
                file.readFully(block, 0, blockLength);
                for (int index = blockLength - 1; index >= 0; index--) {
                    if (block[index] == '\n' && ++separators == lines) {
                        start = blockStart + index + 1;
                        foundStart = true;
                        break;
                    }
                }
                cursor = blockStart;
            }
            if (!foundStart && minimumStart == 0) {
                start = 0;
            }

            byte[] bytes = new byte[Math.toIntExact(fileSize - start)];
            file.seek(start);
            file.readFully(bytes);
            int utf8Start = 0;
            while (utf8Start < bytes.length && (bytes[utf8Start] & 0xC0) == 0x80) {
                utf8Start++;
            }
            return new Tail(
                new String(bytes, utf8Start, bytes.length - utf8Start, StandardCharsets.UTF_8),
                start > 0);
        }
    }

    private record ResolvedLog(Path path, HubLogSource source, String instanceId) {
    }

    private record Tail(String content, boolean truncated) {
    }

}
