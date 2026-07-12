package fun.fengwk.openclihub.core.opencli.catalog;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable in-memory catalog index produced by {@link OpenCliCommandCatalogParser}.
 *
 * <p>The index exposes three lookup paths so the public {@code OpenCliCommandCatalog}
 * implementation can stay thin: canonical key lookup, site+alias lookup and website
 * iteration. The map structures are unmodifiable; callers that need a mutable view
 * must copy explicitly.
 *
 * @author fengwk
 */
public class OpenCliCommandIndex {

    private final Map<String, OpenCliCommand> commands;
    private final Map<String, String> aliasIndex;
    private final Set<String> websites;
    private final Set<String> reservedManagementKeys;

    public OpenCliCommandIndex(
        Map<String, OpenCliCommand> commands,
        Map<String, String> aliasIndex,
        Set<String> websites,
        Set<String> reservedManagementKeys) {
        this.commands = commands;
        this.aliasIndex = aliasIndex;
        this.websites = websites;
        this.reservedManagementKeys = reservedManagementKeys;
    }

    /**
     * Look up by canonical {@code site/name} command key.
     */
    public Optional<OpenCliCommand> findByKey(String commandKey) {
        if (commandKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(commands.get(commandKey));
    }

    /**
     * Look up by site + name-or-alias. Reserved management names always miss.
     */
    public Optional<OpenCliCommand> findBySiteAndAlias(String site, String nameOrAlias) {
        if (site == null || nameOrAlias == null) {
            return Optional.empty();
        }
        if (OpenCliReservedManagementCommands.isReserved(nameOrAlias)) {
            return Optional.empty();
        }
        String canonical = aliasIndex.get(site + "/" + nameOrAlias);
        if (canonical != null) {
            return Optional.ofNullable(commands.get(canonical));
        }
        return Optional.ofNullable(commands.get(site + "/" + nameOrAlias));
    }

    public Map<String, OpenCliCommand> commands() {
        return commands;
    }

    public Map<String, String> aliasIndex() {
        return aliasIndex;
    }

    public Set<String> websites() {
        return websites;
    }

    public Set<String> reservedManagementKeys() {
        return reservedManagementKeys;
    }

    public int size() {
        return commands.size();
    }

}
