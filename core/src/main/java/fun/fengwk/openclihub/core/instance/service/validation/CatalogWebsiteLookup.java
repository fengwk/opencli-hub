package fun.fengwk.openclihub.core.instance.service.validation;

import java.util.Set;

/**
 * Functional lookup of the set of websites known to the OpenCLI command catalog.
 *
 * <p>Decoupled from {@link fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog}
 * so the validator can be unit tested with a static supplier and the catalog dependency
 * remains optional for isolated data-layer tests.
 *
 * @author fengwk
 */
@FunctionalInterface
public interface CatalogWebsiteLookup {

    /**
     * @return known websites; never {@code null}. An empty set signals that the catalog is
     *         unavailable and that validators must refuse any website payload.
     */
    Set<String> knownWebsites();

}
