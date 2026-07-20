package fun.fengwk.openclihub.core.command.service;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandArg;
import fun.fengwk.openclihub.core.command.service.model.HubCommandOutputRule;
import fun.fengwk.openclihub.share.model.command.HubCommandOutputTargetType;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Detects command arguments that represent on-disk outputs and must be platform-managed.
 *
 * <p>These arguments are injected by Hub at execution time and must not appear in the public
 * command catalog API for callers to supply.
 */
public final class HubManagedOutputArguments {

    private static final Set<String> DIRECTORY_NAMES = Set.of("op", "out", "outdir");
    private static final Set<String> CANDIDATE_NAMES = Set.of("op", "out", "outdir", "output", "path");

    private HubManagedOutputArguments() {
    }

    /**
     * Whether this catalog argument is an output path Hub should fully manage.
     */
    public static boolean isManagedOutputArg(OpenCliCommandArg arg) {
        return classify(arg) != null;
    }

    /**
     * Build a synthetic output rule for the first managed output argument on the command.
     * Returns {@code null} when the command has no managed output argument.
     */
    public static HubCommandOutputRule syntheticRule(OpenCliCommand command) {
        if (command == null || command.getArgs() == null) {
            return null;
        }
        for (OpenCliCommandArg arg : command.getArgs()) {
            HubCommandOutputTargetType type = classify(arg);
            if (type == null) {
                continue;
            }
            HubCommandOutputRule rule = new HubCommandOutputRule();
            rule.setCommandKey(command.getCommandKey());
            rule.setArgumentName(arg.getName());
            rule.setTargetType(type);
            if (type == HubCommandOutputTargetType.FILE) {
                rule.setFileName("output");
            }
            return rule;
        }
        return null;
    }

    /**
     * Whether a public catalog argument should be hidden because Hub manages it.
     */
    public static boolean shouldHideFromPublicCatalog(OpenCliCommandArg arg, HubCommandOutputRule rule) {
        if (arg == null || arg.getName() == null) {
            return true;
        }
        if (isManagedOutputArg(arg)) {
            return true;
        }
        return rule != null
            && rule.getArgumentName() != null
            && rule.getArgumentName().equals(arg.getName());
    }

    static HubCommandOutputTargetType classify(OpenCliCommandArg arg) {
        if (arg == null || arg.getName() == null || arg.getName().isBlank() || arg.isPositional()) {
            return null;
        }
        String name = arg.getName().trim().toLowerCase(Locale.ROOT);
        if (!CANDIDATE_NAMES.contains(name)) {
            return null;
        }
        String help = arg.getHelp() == null ? "" : arg.getHelp().toLowerCase(Locale.ROOT);

        // Business destination / cloud folder moves are not local file outputs.
        if (isBusinessPathHelp(help)) {
            return null;
        }

        if (DIRECTORY_NAMES.contains(name)) {
            return HubCommandOutputTargetType.DIRECTORY;
        }
        if ("output".equals(name) || "path".equals(name)) {
            if (isFilePathHelp(help)) {
                return HubCommandOutputTargetType.FILE;
            }
            // download/output directory, or empty/generic help treated as directory.
            if (isDirectoryPathHelp(help) || help.isBlank() || help.contains("download")
                || help.contains("save") || help.contains("导出") || help.contains("保存")) {
                return HubCommandOutputTargetType.DIRECTORY;
            }
            // Conservative default for remaining "output"/"path": only manage clear directory cases.
            if ("output".equals(name)) {
                return HubCommandOutputTargetType.DIRECTORY;
            }
            return null;
        }
        return null;
    }

    private static boolean isFilePathHelp(String help) {
        return help.contains("file path")
            || help.contains("output file")
            || help.contains("输出文件")
            || help.contains("output image path")
            || (help.contains("file") && !help.contains("directory") && !help.contains("dir")
                && !help.contains("目录"));
    }

    private static boolean isDirectoryPathHelp(String help) {
        return help.contains("directory")
            || help.contains("dir")
            || help.contains("目录")
            || help.contains("folder")
            || help.contains("output directory")
            || help.contains("download directory");
    }

    private static boolean isBusinessPathHelp(String help) {
        return help.contains("destination keyword")
            || help.contains("iata")
            || help.contains("arrival")
            || help.contains("folder path to list")
            || help.contains("destination folder")
            || help.contains("范围截止")
            || help.contains("city");
    }

    /** Visible for tests. */
    static List<String> candidateNames() {
        return List.copyOf(CANDIDATE_NAMES);
    }
}
