package fun.fengwk.openclihub.core.opencli.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommand;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandArg;
import fun.fengwk.openclihub.share.model.command.HubCommandAccess;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses OpenCLI {@code cli-manifest.json} payload into Hub's canonical command model.
 *
 * <p>The parser is intentionally tolerant: it filters non-browser commands out of the public
 * catalog and treats the management command names reserved by the design document as never
 * matching a website command. The result is deterministic: iteration order follows the
 * manifest declaration order; canonical command keys use {@code site/name} and aliases never
 * collide because duplicates encountered during normalization cause a hard parse failure.
 *
 * @author fengwk
 */
public class OpenCliCommandCatalogParser {

    private static final String MANAGEMENT_RESERVED_HINT = "__management_reserved__";

    private final ObjectMapper objectMapper;

    public OpenCliCommandCatalogParser() {
        this(createDefaultObjectMapper());
    }

    public OpenCliCommandCatalogParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parse the raw JSON text into a populated catalog index.
     *
     * @param json raw cli-manifest.json payload
     * @return parsed catalog index, never null
     * @throws OpenCliCatalogParseException when the payload cannot be parsed or violates
     *                                      catalog invariants (duplicate aliases etc.)
     */
    public OpenCliCommandIndex parse(String json) {
        if (json == null || json.isBlank()) {
            throw new OpenCliCatalogParseException("Catalog JSON must not be blank");
        }
        List<OpenCliManifestEntry> entries;
        try {
            entries = objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException ex) {
            throw new OpenCliCatalogParseException("Failed to parse cli-manifest.json", ex);
        }
        return parse(entries);
    }

    /**
     * Parse a JSON input stream.
     *
     * @param in stream of cli-manifest.json
     * @return parsed catalog index, never null
     */
    public OpenCliCommandIndex parse(InputStream in) {
        if (in == null) {
            throw new OpenCliCatalogParseException("Catalog input stream must not be null");
        }
        List<OpenCliManifestEntry> entries;
        try {
            entries = objectMapper.readValue(in, new TypeReference<>() { });
        } catch (IOException ex) {
            throw new OpenCliCatalogParseException("Failed to parse cli-manifest.json stream", ex);
        }
        return parse(entries);
    }

    /**
     * Parse a JSON file from the local filesystem. The file is fully buffered; no
     * streaming is required because the manifest is small.
     */
    public OpenCliCommandIndex parse(Path path) {
        if (path == null) {
            throw new OpenCliCatalogParseException("Catalog path must not be null");
        }
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in);
        } catch (IOException ex) {
            throw new OpenCliCatalogParseException(
                "Failed to read cli-manifest.json from " + path, ex);
        }
    }

    private OpenCliCommandIndex parse(List<OpenCliManifestEntry> entries) {
        // LinkedHashMap preserves declaration order; values are command indices keyed by canonical key.
        Map<String, OpenCliCommand> commands = new LinkedHashMap<>();
        // alias -> canonical key, e.g. "autohome/series" -> "autohome/brand".
        Map<String, String> aliasIndex = new LinkedHashMap<>();
        Set<String> websites = new LinkedHashSet<>();
        Set<String> reserved = new LinkedHashSet<>();
        Set<String> seenKeys = new LinkedHashSet<>();

        if (entries != null) {
            for (OpenCliManifestEntry entry : entries) {
                if (entry == null) {
                    continue;
                }
                // Public catalog only exposes browser commands per design.
                if (!entry.isBrowser()) {
                    continue;
                }
                OpenCliCommand command = convertEntry(entry);
                String canonicalKey = command.getCommandKey();
                if (canonicalKey == null || canonicalKey.isBlank()) {
                    throw new OpenCliCatalogParseException(
                        "Manifest entry is missing site/name: " + entry);
                }
                if (seenKeys.contains(canonicalKey)) {
                    throw new OpenCliCatalogParseException(
                        "Duplicate canonical command key: " + canonicalKey);
                }
                seenKeys.add(canonicalKey);
                commands.put(canonicalKey, command);
                websites.add(command.getSite());

                // Register aliases under site/alias; the lookup method normalizes the same way.
                List<String> aliases = command.getAliases();
                if (aliases != null) {
                    for (String alias : aliases) {
                        if (alias == null || alias.isBlank()) {
                            continue;
                        }
                        String trimmed = alias.trim();
                        String aliasKey = command.getSite() + "/" + trimmed;
                        String previous = aliasIndex.putIfAbsent(aliasKey, canonicalKey);
                        if (previous != null && !previous.equals(canonicalKey)) {
                            throw new OpenCliCatalogParseException(
                                "Alias " + aliasKey + " maps to multiple canonical commands: "
                                    + previous + " and " + canonicalKey);
                        }
                    }
                }
            }
        }

        // Reserved management names never appear as a website command in cli-manifest.json,
        // but we explicitly stamp them with a sentinel alias so that a malicious caller
        // attempting to invoke "daemon/list" cannot find a public entry via alias lookup.
        for (String reservedName : OpenCliReservedManagementCommands.NAMES) {
            reserved.add(commandKey(reservedName, MANAGEMENT_RESERVED_HINT));
        }

        return new OpenCliCommandIndex(
            Collections.unmodifiableMap(commands),
            Collections.unmodifiableMap(aliasIndex),
            Collections.unmodifiableSet(websites),
            Collections.unmodifiableSet(reserved));
    }

    private OpenCliCommand convertEntry(OpenCliManifestEntry entry) {
        OpenCliCommand command = new OpenCliCommand();
        command.setSite(entry.getSite());
        command.setName(entry.getName());
        command.setCommandKey(commandKey(entry.getSite(), entry.getName()));
        command.setAliases(sanitizeAliases(entry.getAliases()));
        command.setDescription(entry.getDescription() == null ? "" : entry.getDescription());
        command.setAccess(parseAccess(entry.getAccess()));
        command.setBrowser(true);
        command.setArgs(convertArgs(entry.getArgs()));
        command.setSiteSession(parseSiteSession(entry.getSiteSession()));
        command.setDefaultWindowMode(entry.getDefaultWindowMode());
        return command;
    }

    private List<OpenCliCommandArg> convertArgs(List<OpenCliManifestArg> rawArgs) {
        if (rawArgs == null || rawArgs.isEmpty()) {
            return List.of();
        }
        List<OpenCliCommandArg> result = new ArrayList<>(rawArgs.size());
        for (OpenCliManifestArg raw : rawArgs) {
            if (raw == null || raw.getName() == null || raw.getName().isBlank()) {
                continue;
            }
            String type = raw.getType() == null ? "string" : raw.getType();
            // The pinned OpenCLI manifest omits `valueRequired` when the option is a
            // typed value (the OpenCLI commander adapter treats it as required). We
            // mirror that behavior so Hub callers can pass `--name value` consistently.
            boolean rawValueRequired = Boolean.TRUE.equals(raw.getValueRequired());
            boolean rawRequired = Boolean.TRUE.equals(raw.getRequired());
            boolean effectiveValueRequired;
            if (raw.getValueRequired() == null) {
                effectiveValueRequired = !isBoolean(type);
            } else {
                effectiveValueRequired = rawValueRequired;
            }
            boolean positional = Boolean.TRUE.equals(raw.getPositional());

            OpenCliCommandArg arg = new OpenCliCommandArg();
            arg.setName(raw.getName().trim());
            arg.setType(type);
            arg.setRequired(rawRequired);
            arg.setValueRequired(effectiveValueRequired);
            arg.setPositional(positional);
            arg.setChoices(raw.getChoices() == null ? List.of() : List.copyOf(raw.getChoices()));
            arg.setDefaultValue(raw.getDefaultValue());
            arg.setHelp(raw.getHelp() == null ? "" : raw.getHelp());
            result.add(arg);
        }
        return result;
    }

    private static boolean isBoolean(String type) {
        if (type == null) {
            return false;
        }
        switch (type.trim().toLowerCase()) {
            case "bool":
            case "boolean":
                return true;
            default:
                return false;
        }
    }

    private static List<String> sanitizeAliases(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String alias : aliases) {
            if (alias != null && !alias.isBlank()) {
                set.add(alias.trim());
            }
        }
        return new ArrayList<>(set);
    }

    private static HubCommandAccess parseAccess(String access) {
        if (access == null) {
            return null;
        }
        try {
            return HubCommandAccess.valueOf(access.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static SiteSessionMode parseSiteSession(String mode) {
        if (mode == null || mode.isBlank()) {
            // Treat missing/blank metadata as absent; OpenCLI defaults an absent declaration to ephemeral.
            return SiteSessionMode.EPHEMERAL;
        }
        try {
            return SiteSessionMode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            // Unrecognized non-blank values stay null and fail-safe to EXCLUSIVE downstream
            return null;
        }
    }

    private static String commandKey(String site, String name) {
        return site + "/" + name;
    }

    private static ObjectMapper createDefaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

}
