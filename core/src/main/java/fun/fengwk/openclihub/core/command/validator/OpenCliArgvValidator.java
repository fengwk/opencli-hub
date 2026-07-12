package fun.fengwk.openclihub.core.command.validator;

import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandArg;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog;
import fun.fengwk.openclihub.core.opencli.catalog.OpenCliReservedManagementCommands;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catalog-driven argv validator and normalizer.
 *
 * <p>The validator refuses to pass raw caller input to the executor. It re-parses the
 * supplied argv against the resolved command's declared arguments, enforces Hub-exclusive
 * options, validates types/choices/required flags and rebuilds a canonical argv list that
 * downstream executors (M5) can forward to the OpenCLI process via {@code ProcessBuilder}.
 *
 * <p>Boolean flag arguments ({@code valueRequired=false, type=boolean}) are normalized as
 * a single {@code --name} token: the validator rejects {@code --name=value} and
 * {@code --name value} forms so Hub cannot accidentally turn a boolean flag into a
 * value-bearing option. The normalized argv never carries {@code true}/{@code false} for
 * these options; callers that need the boolean decision can read it from
 * {@link NormalizedOpenCliArgv#getNamedValue(String)} which records {@code true}.
 *
 * <p>No shell concatenation is performed anywhere in this class.
 *
 * @author fengwk
 */
public class OpenCliArgvValidator {

    private final OpenCliCommandCatalog catalog;

    public OpenCliArgvValidator(OpenCliCommandCatalog catalog) {
        if (catalog == null) {
            throw new IllegalArgumentException("catalog must not be null");
        }
        this.catalog = catalog;
    }

    /**
     * Validate and normalize the caller-supplied argv.
     *
     * @param argv the raw caller argv; must start with a website and a name-or-alias
     * @return a {@link NormalizedOpenCliArgv} with the rebuilt argv and resolved metadata
     */
    public NormalizedOpenCliArgv validate(List<String> argv) {
        if (argv == null) {
            throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_ARGUMENT_INVALID,
                "argv must not be null");
        }
        if (argv.size() < 2) {
            throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_ARGUMENT_INVALID,
                "argv must start with <site> <command>");
        }

        String site = argv.get(0);
        String nameOrAlias = argv.get(1);
        if (site == null || site.isBlank()) {
            throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_COMMAND_NOT_FOUND,
                "argv site must not be blank");
        }
        if (nameOrAlias == null || nameOrAlias.isBlank()) {
            throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_COMMAND_NOT_FOUND,
                "argv command name must not be blank");
        }
        if (OpenCliReservedManagementCommands.isReserved(site)
            || OpenCliReservedManagementCommands.isReserved(nameOrAlias)) {
            throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_COMMAND_NOT_PUBLIC,
                "OpenCLI management command is not exposed: " + site + "/" + nameOrAlias);
        }
        if (!catalog.containsWebsite(site)) {
            throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_COMMAND_NOT_FOUND,
                "Unknown OpenCLI site: " + site);
        }

        Optional<OpenCliCommand> maybeCommand = catalog.findPublicCommand(site, nameOrAlias);
        if (maybeCommand.isEmpty()) {
            throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_COMMAND_NOT_FOUND,
                "Unknown OpenCLI command: " + site + "/" + nameOrAlias);
        }
        OpenCliCommand command = maybeCommand.get();
        String canonicalKey = command.getCommandKey();

        Map<String, OpenCliCommandArg> positionalByName = new LinkedHashMap<>();
        Map<String, OpenCliCommandArg> namedByName = new LinkedHashMap<>();
        for (OpenCliCommandArg arg : command.getArgs()) {
            if (arg.isPositional()) {
                positionalByName.put(arg.getName(), arg);
            } else {
                namedByName.put(arg.getName(), arg);
            }
        }

        List<String> positionalValues = new ArrayList<>();
        Map<String, List<String>> namedValues = new LinkedHashMap<>();
        List<String> normalizedArgv = new ArrayList<>();
        normalizedArgv.add(command.getSite());
        normalizedArgv.add(command.getName());

        int cursor = 2;
        while (cursor < argv.size()) {
            String token = argv.get(cursor);
            if (token == null || token.isBlank()) {
                throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_ARGUMENT_INVALID,
                    "Empty token at position " + cursor);
            }
            if ("--".equals(token)) {
                throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_ARGUMENT_INVALID,
                    "Literal `--` separator is not supported by the Hub validator");
            }
            if (OpenCliReservedArguments.isReserved(token)) {
                throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_RESERVED_ARGUMENT,
                    "Hub-owned option must not be supplied by the caller: " + token, token);
            }
            if (!token.startsWith("-")) {
                OpenCliCommandArg positional = nextPositional(positionalByName, positionalValues.size());
                if (positional == null) {
                    throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_ARGUMENT_INVALID,
                        "Unexpected positional argument at position " + cursor + ": " + token);
                }
                validateValue(positional, token);
                positionalValues.add(token);
                normalizedArgv.add(token);
                cursor += 1;
                continue;
            }

            // Named option: either `--name value`, `--name=value` or `--name` (boolean flag).
            String name;
            String inlineValue;
            int eq = token.indexOf('=');
            if (eq > 0) {
                name = token.substring(0, eq);
                inlineValue = token.substring(eq + 1);
            } else {
                name = token;
                inlineValue = null;
            }
            // The pinned OpenCLI manifest does not declare short named options, so any
            // short flag here is reserved for Hub by convention.
            if (name.length() == 2 && name.startsWith("-")) {
                throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_RESERVED_ARGUMENT,
                    "Hub-owned short option must not be supplied by the caller: " + token, token);
            }
            String argName = name.startsWith("--") ? name.substring(2) : name.substring(1);
            OpenCliCommandArg declared = namedByName.get(argName);
            if (declared == null) {
                throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_ARGUMENT_NOT_ALLOWED,
                    "Unknown OpenCLI option: " + name, name);
            }

            boolean isBooleanFlag = isBooleanFlag(declared);
            if (inlineValue != null) {
                if (isBooleanFlag) {
                    // --name=value is not a valid boolean-flag invocation. Commander's
                    // no-value boolean options only accept `--name`; refusing inline
                    // values prevents callers from smuggling arbitrary strings in.
                    throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_ARGUMENT_INVALID,
                        "Boolean flag --" + declared.getName()
                            + " must not carry an inline value", declared.getName());
                }
                validateValue(declared, inlineValue);
                namedValues.computeIfAbsent(declared.getName(), ignored -> new ArrayList<>()).add(inlineValue);
                appendNamed(declared, inlineValue, normalizedArgv);
                cursor += 1;
                continue;
            }

            if (declared.isValueRequired() || declared.isRequired()) {
                if (cursor + 1 >= argv.size()) {
                    throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_ARGUMENT_INVALID,
                        "OpenCLI option requires a value: " + name);
                }
                cursor += 1;
                String value = argv.get(cursor);
                if (value == null) {
                    throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_ARGUMENT_INVALID,
                        "OpenCLI option value must not be null: " + name);
                }
                if (OpenCliReservedArguments.isReserved(value)) {
                    throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_RESERVED_ARGUMENT,
                        "Hub-owned option value must not be supplied by the caller: " + value, value);
                }
                validateValue(declared, value);
                namedValues.computeIfAbsent(declared.getName(), ignored -> new ArrayList<>()).add(value);
                appendNamed(declared, value, normalizedArgv);
                cursor += 1;
                continue;
            }

            if (isBooleanFlag) {
                // Boolean flag with no value: present means true. Record the decision in
                // namedValues so downstream code can branch on it, but emit only `--name`
                // in normalized argv because the pinned OpenCLI commander treats the
                // token presence as the signal.
                namedValues.computeIfAbsent(declared.getName(), ignored -> new ArrayList<>()).add("true");
                normalizedArgv.add("--" + declared.getName());
                cursor += 1;
                continue;
            }

            // Optional non-boolean with no value supplied: omit from normalized argv (default applies).
            cursor += 1;
        }

        // Required-arity checks.
        for (Map.Entry<String, OpenCliCommandArg> entry : positionalByName.entrySet()) {
            OpenCliCommandArg arg = entry.getValue();
            if (arg.isRequired() && !positionalValuesMeetsPositional(positionalByName, arg, positionalValues)) {
                throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_ARGUMENT_INVALID,
                    "Missing required positional argument: " + arg.getName(), arg.getName());
            }
        }
        for (OpenCliCommandArg arg : command.getArgs()) {
            if (arg.isPositional()) {
                continue;
            }
            if (arg.isRequired() && !namedValues.containsKey(arg.getName())) {
                throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_ARGUMENT_INVALID,
                    "Missing required OpenCLI option: --" + arg.getName(), arg.getName());
            }
        }

        return new NormalizedOpenCliArgv(
            command,
            canonicalKey,
            positionalValues,
            namedValues,
            normalizedArgv);
    }

    private static boolean isBooleanFlag(OpenCliCommandArg arg) {
        return !arg.isValueRequired() && !arg.isRequired()
            && OpenCliArgumentType.BOOLEAN == OpenCliArgumentType.of(arg.getType());
    }

    private static void appendNamed(OpenCliCommandArg declared, String value, List<String> normalizedArgv) {
        if (declared.isPositional()) {
            normalizedArgv.add("--" + declared.getName() + "=" + value);
        } else {
            normalizedArgv.add("--" + declared.getName());
            normalizedArgv.add(value);
        }
    }

    private static OpenCliCommandArg nextPositional(
        Map<String, OpenCliCommandArg> positionalByName, int seenCount) {
        if (seenCount >= positionalByName.size()) {
            return null;
        }
        int idx = 0;
        for (OpenCliCommandArg arg : positionalByName.values()) {
            if (idx == seenCount) {
                return arg;
            }
            idx += 1;
        }
        return null;
    }

    private static boolean positionalValuesMeetsPositional(
        Map<String, OpenCliCommandArg> positionalByName, OpenCliCommandArg target, List<String> values) {
        // Treat positional arguments as supplied in declaration order.
        int idx = 0;
        for (OpenCliCommandArg arg : positionalByName.values()) {
            if (arg == target) {
                return values.size() > idx;
            }
            idx += 1;
        }
        return false;
    }

    private static void validateValue(OpenCliCommandArg arg, String value) {
        OpenCliArgumentType type = OpenCliArgumentType.of(arg.getType());
        if (!type.accepts(value)) {
            throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_ARGUMENT_INVALID,
                "OpenCLI option --" + arg.getName() + " expects " + type + " but got: " + value,
                arg.getName());
        }
        if (arg.getChoices() != null && !arg.getChoices().isEmpty()
            && !arg.getChoices().contains(value)) {
            throw new OpenCliArgvValidationException(HubErrorCodes.OPENCLI_ARGUMENT_INVALID,
                "OpenCLI option --" + arg.getName() + " value not in choices: " + value
                    + " (allowed=" + arg.getChoices() + ")",
                arg.getName());
        }
    }

}
