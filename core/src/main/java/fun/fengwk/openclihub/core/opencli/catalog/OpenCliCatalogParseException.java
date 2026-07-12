package fun.fengwk.openclihub.core.opencli.catalog;

/**
 * Thrown when the OpenCLI cli-manifest.json payload cannot be parsed or violates catalog
 * invariants. The cause is always recoverable by correcting the pinned OpenCLI artifact
 * or by adjusting the parser to recognize a new field.
 *
 * @author fengwk
 */
public class OpenCliCatalogParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OpenCliCatalogParseException(String message) {
        super(message);
    }

    public OpenCliCatalogParseException(String message, Throwable cause) {
        super(message, cause);
    }

}
