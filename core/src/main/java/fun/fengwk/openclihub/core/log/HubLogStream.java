package fun.fengwk.openclihub.core.log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import lombok.Getter;

/**
 * Open fixed-source log file stream. The caller owns and must close the stream.
 *
 * @author fengwk
 */
@Getter
public final class HubLogStream implements AutoCloseable {

    private final String fileName;
    private final long size;
    private final InputStream inputStream;

    public HubLogStream(String fileName, long size, InputStream inputStream) {
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.size = size;
        this.inputStream = Objects.requireNonNull(inputStream, "inputStream");
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }

}
