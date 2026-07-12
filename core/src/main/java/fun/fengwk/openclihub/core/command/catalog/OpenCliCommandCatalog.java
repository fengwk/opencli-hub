package fun.fengwk.openclihub.core.command.catalog;

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

}
