package fun.fengwk.openclihub.core.opencli.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Argument entry shape consumed by Hub from the pinned OpenCLI cli-manifest.json.
 *
 * <p>Field naming matches the OpenCLI manifest schema
 * ({@code required}/{@code valueRequired}/{@code positional}/{@code default}).
 *
 * @author fengwk
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenCliManifestArg {

    private String name;
    private String type;
    private Boolean required;
    private Boolean valueRequired;
    private Boolean positional;
    private List<String> choices;
    @JsonProperty("default")
    private Object defaultValue;
    private String help;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Boolean getRequired() {
        return required;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }

    public Boolean getValueRequired() {
        return valueRequired;
    }

    public void setValueRequired(Boolean valueRequired) {
        this.valueRequired = valueRequired;
    }

    public Boolean getPositional() {
        return positional;
    }

    public void setPositional(Boolean positional) {
        this.positional = positional;
    }

    public List<String> getChoices() {
        return choices;
    }

    public void setChoices(List<String> choices) {
        this.choices = choices;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
    }

    public String getHelp() {
        return help;
    }

    public void setHelp(String help) {
        this.help = help;
    }

}
