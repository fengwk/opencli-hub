package fun.fengwk.openclihub.core.instance.service.validation;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Spring adapter that exposes the catalog website set to validators while tolerating the
 * absence of an {@link OpenCliCommandCatalog} bean (the M1 catalog implementation is
 * delivered in parallel and is not required for the M3 data layer).
 *
 * <p>If the catalog bean is missing, {@link #knownWebsites()} returns an empty set so that
 * {@link HubInstanceValidator} fails loudly on website validation rather than silently
 * accepting arbitrary websites.
 *
 * @author fengwk
 */
@Component
public class CatalogWebsiteSource implements CatalogWebsiteLookup {

    private final ObjectProvider<OpenCliCommandCatalog> catalogProvider;

    public CatalogWebsiteSource(ObjectProvider<OpenCliCommandCatalog> catalogProvider) {
        this.catalogProvider = catalogProvider;
    }

    /**
     * Returns the set of websites known to the catalog, never {@code null}.
     * Returns an empty set when the catalog bean is absent so validators can detect it.
     */
    @Override
    public Set<String> knownWebsites() {
        OpenCliCommandCatalog catalog = catalogProvider.getIfAvailable();
        if (catalog == null) {
            return Set.of();
        }
        Set<String> raw = catalog.listWebsites();
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(raw);
    }

}
