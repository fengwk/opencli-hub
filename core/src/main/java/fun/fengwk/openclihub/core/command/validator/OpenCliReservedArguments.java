package fun.fengwk.openclihub.core.command.validator;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Names of OpenCLI control arguments that Hub exclusively owns and that callers must
 * never pass through.
 *
 * <p>These match the {@code --profile}/{@code -f --format}/{@code --site-session}/
 * {@code --keep-tab}/{@code --window}/{@code --trace}/{@code -v --verbose}/{@code -h --help}/
 * {@code -V --version} options listed in the design document. A short alias
 * ({@code -f}, {@code -v}, {@code -h}, {@code -V}) is part of the same block because the
 * OpenCLI root option parser binds them globally.
 *
 * @author fengwk
 */
public final class OpenCliReservedArguments {

    /**
     * Long-form reserved option names, including the leading double dash.
     */
    public static final Set<String> LONG_NAMES;

    /**
     * Short-form reserved option names, including the leading single dash.
     */
    public static final Set<String> SHORT_NAMES;

    /**
     * All reserved tokens (long + short) for fast {@code contains} lookups.
     */
    public static final Set<String> ALL;

    static {
        Set<String> longNames = new LinkedHashSet<>();
        longNames.add("--profile");
        longNames.add("--format");
        longNames.add("--site-session");
        longNames.add("--keep-tab");
        longNames.add("--window");
        longNames.add("--trace");
        longNames.add("--verbose");
        longNames.add("--help");
        longNames.add("--version");
        LONG_NAMES = Collections.unmodifiableSet(longNames);

        Set<String> shortNames = new LinkedHashSet<>();
        shortNames.add("-f");
        shortNames.add("-v");
        shortNames.add("-h");
        shortNames.add("-V");
        SHORT_NAMES = Collections.unmodifiableSet(shortNames);

        Set<String> all = new LinkedHashSet<>();
        all.addAll(longNames);
        all.addAll(shortNames);
        ALL = Collections.unmodifiableSet(all);
    }

    private OpenCliReservedArguments() {
    }

    /**
     * Whether the given raw token (including leading dashes) is reserved for Hub.
     */
    public static boolean isReserved(String token) {
        if (token == null) {
            return false;
        }
        if (ALL.contains(token)) {
            return true;
        }
        // Strip an optional `=value` suffix so `--format=json` is also rejected.
        int eq = token.indexOf('=');
        if (eq > 0) {
            return ALL.contains(token.substring(0, eq));
        }
        return false;
    }

}