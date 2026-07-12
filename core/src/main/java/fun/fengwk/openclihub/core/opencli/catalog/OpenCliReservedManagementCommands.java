package fun.fengwk.openclihub.core.opencli.catalog;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Names of OpenCLI management commands that the Hub must never expose as public commands.
 *
 * <p>The design document excludes {@code browser}, {@code daemon}, {@code profile},
 * {@code plugin}, {@code doctor}, {@code list}, {@code external} and {@code completion}.
 * Because OpenCLI's {@code cli-manifest.json} never lists these, the runtime guarantee is
 * that the public catalog never matches them. The set is referenced by both the parser
 * (to stamp reserved aliases) and the validator (to reject argv that names them).
 *
 * @author fengwk
 */
public final class OpenCliReservedManagementCommands {

    /**
     * Reserved management command names. Use {@link LinkedHashSet} for deterministic iteration.
     */
    public static final Set<String> NAMES;

    static {
        Set<String> set = new LinkedHashSet<>();
        set.add("browser");
        set.add("daemon");
        set.add("profile");
        set.add("plugin");
        set.add("doctor");
        set.add("list");
        set.add("external");
        set.add("completion");
        NAMES = java.util.Collections.unmodifiableSet(set);
    }

    private OpenCliReservedManagementCommands() {
    }

    /**
     * Whether the given command name is structurally excluded from the public catalog.
     */
    public static boolean isReserved(String name) {
        return name != null && NAMES.contains(name);
    }

}