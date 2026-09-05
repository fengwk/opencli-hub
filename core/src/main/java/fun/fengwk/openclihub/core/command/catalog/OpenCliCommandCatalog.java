package fun.fengwk.openclihub.core.command.catalog;

import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only public command catalog contract shared by independent modules.
 *
 * @author fengwk
 */
public interface OpenCliCommandCatalog {

    List<OpenCliCommand> listPublicCommands();

    Optional<OpenCliCommand> findPublicCommand(String site, String nameOrAlias);

    Set<String> listWebsites();

    default boolean containsWebsite(String website) {
        return website != null && listWebsites().contains(website);
    }

    /**
     * Lists websites that declare at least one public command with
     * {@link SiteSessionMode#PERSISTENT}. Preserves declaration order and returns an
     * unmodifiable set.
     */
    default Set<String> listPersistentWebsites() {
        Set<String> persistent = new LinkedHashSet<>();
        List<OpenCliCommand> commands = listPublicCommands();
        if (commands != null) {
            for (OpenCliCommand command : commands) {
                if (command != null
                    && command.isBrowser()
                    && command.getSite() != null
                    && !command.getSite().isBlank()
                    && command.getSiteSession() == SiteSessionMode.PERSISTENT) {
                    persistent.add(command.getSite());
                }
            }
        }
        return Collections.unmodifiableSet(persistent);
    }

    /**
     * Checks if the given website has at least one public persistent command in the catalog.
     */
    default boolean containsPersistentWebsite(String website) {
        return website != null && !website.isBlank() && listPersistentWebsites().contains(website);
    }

    /**
     * Reloads the in-memory catalog from its backing source. Default is a no-op so
     * lightweight test doubles do not need a reload implementation.
     */
    default void reload() {
    }

}
