package fun.fengwk.openclihub.core.opencli.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Single command entry as it appears in the OpenCLI cli-manifest.json array.
 *
 * <p>Only fields consumed by Hub are declared; unknown fields are tolerated so the pinned
 * OpenCLI version may add metadata without breaking the loader.
 *
 * @author fengwk
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenCliManifestEntry {

    private String site;
    private String name;
    private List<String> aliases;
    private String description;
    private String access;
    private boolean browser;
    private List<OpenCliManifestArg> args;
    private String siteSession;
    private String defaultWindowMode;

    public String getSite() {
        return site;
    }

    public void setSite(String site) {
        this.site = site;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public void setAliases(List<String> aliases) {
        this.aliases = aliases;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAccess() {
        return access;
    }

    public void setAccess(String access) {
        this.access = access;
    }

    public boolean isBrowser() {
        return browser;
    }

    public void setBrowser(boolean browser) {
        this.browser = browser;
    }

    public List<OpenCliManifestArg> getArgs() {
        return args;
    }

    public void setArgs(List<OpenCliManifestArg> args) {
        this.args = args;
    }

    public String getSiteSession() {
        return siteSession;
    }

    public void setSiteSession(String siteSession) {
        this.siteSession = siteSession;
    }

    public String getDefaultWindowMode() {
        return defaultWindowMode;
    }

    public void setDefaultWindowMode(String defaultWindowMode) {
        this.defaultWindowMode = defaultWindowMode;
    }

}
