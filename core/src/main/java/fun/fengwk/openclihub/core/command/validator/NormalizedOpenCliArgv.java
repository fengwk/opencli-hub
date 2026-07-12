package fun.fengwk.openclihub.core.command.validator;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/**
 * Catalog-driven normalized argv plus the resolved command metadata.
 *
 * <p>The {@link #positionalValues} list preserves caller-supplied positional arguments in
 * declaration order; the {@link #namedValues} map captures named options. The
 * {@link #normalizedArgv} list is what the executor should pass to {@code opencli} and is
 * rebuilt by the validator so it never reflects raw caller input.
 *
 * @author fengwk
 */
@Getter
public class NormalizedOpenCliArgv {

    private final OpenCliCommand command;
    private final String canonicalKey;
    private final List<String> positionalValues;
    private final Map<String, List<String>> namedValues;
    private final List<String> normalizedArgv;

    public NormalizedOpenCliArgv(
        OpenCliCommand command,
        String canonicalKey,
        List<String> positionalValues,
        Map<String, List<String>> namedValues,
        List<String> normalizedArgv) {
        this.command = command;
        this.canonicalKey = canonicalKey;
        this.positionalValues = Collections.unmodifiableList(new ArrayList<>(positionalValues));
        Map<String, List<String>> copied = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : namedValues.entrySet()) {
            copied.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        this.namedValues = Collections.unmodifiableMap(copied);
        this.normalizedArgv = Collections.unmodifiableList(new ArrayList<>(normalizedArgv));
    }

    /**
     * First value for a named option, or {@code null} when the option was not supplied.
     */
    public String getNamedValue(String name) {
        List<String> values = namedValues.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

}
