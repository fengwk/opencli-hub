package fun.fengwk.openclihub.core.opencli.catalog;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link OpenCliCommandCatalog} backed by an {@link OpenCliCatalogSource}.
 *
 * <p>The implementation eagerly loads and parses the manifest on first use so any
 * deploy-time problem (missing binary, malformed JSON) surfaces immediately. Subsequent
 * lookups are served from an in-memory {@link OpenCliCommandIndex} and remain
 * thread-safe.
 *
 * @author fengwk
 */
@Slf4j
public class DefaultOpenCliCommandCatalog implements OpenCliCommandCatalog {

    private final OpenCliCatalogSource source;
    private final OpenCliCommandCatalogParser parser;
    private final AtomicReference<OpenCliCommandIndex> indexRef = new AtomicReference<>();

    public DefaultOpenCliCommandCatalog(OpenCliCatalogSource source) {
        this(source, new OpenCliCommandCatalogParser());
    }

    public DefaultOpenCliCommandCatalog(OpenCliCatalogSource source, OpenCliCommandCatalogParser parser) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (parser == null) {
            throw new IllegalArgumentException("parser must not be null");
        }
        this.source = source;
        this.parser = parser;
    }

    @Override
    public List<OpenCliCommand> listPublicCommands() {
        return new ArrayList<>(currentIndex().commands().values());
    }

    @Override
    public Optional<OpenCliCommand> findPublicCommand(String site, String nameOrAlias) {
        if (site == null || nameOrAlias == null) {
            return Optional.empty();
        }
        if (OpenCliReservedManagementCommands.isReserved(nameOrAlias)) {
            return Optional.empty();
        }
        return currentIndex().findBySiteAndAlias(site, nameOrAlias);
    }

    @Override
    public Set<String> listWebsites() {
        return currentIndex().websites();
    }

    /**
     * Force a reload from the configured source. Useful after OpenCLI upgrades
     * or admin-triggered cache invalidation.
     */
    public synchronized void reload() {
        OpenCliCommandIndex fresh = loadIndex();
        indexRef.set(fresh);
        log.info("OpenCLI catalog reloaded with {} public commands across {} websites",
            fresh.size(), fresh.websites().size());
    }

    /**
     * Replace the in-memory index directly. Intended for tests that want to assert
     * against a hand-built index without going through the source.
     */
    public void replaceIndex(OpenCliCommandIndex index) {
        if (index == null) {
            throw new IllegalArgumentException("index must not be null");
        }
        indexRef.set(index);
    }

    public OpenCliCommandIndex currentIndex() {
        OpenCliCommandIndex cached = indexRef.get();
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = indexRef.get();
            if (cached == null) {
                cached = loadIndex();
                indexRef.set(cached);
            }
            return cached;
        }
    }

    private OpenCliCommandIndex loadIndex() {
        try (InputStream in = source.open()) {
            OpenCliCommandIndex parsed = parser.parse(in);
            log.info("Loaded OpenCLI catalog with {} public browser commands across {} websites",
                parsed.size(), parsed.websites().size());
            return parsed;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load OpenCLI catalog", ex);
        }
    }

    /**
     * Expose the reserved management keys so tests can assert the structural exclusion.
     */
    public Set<String> reservedManagementKeys() {
        return Collections.unmodifiableSet(currentIndex().reservedManagementKeys());
    }

}
