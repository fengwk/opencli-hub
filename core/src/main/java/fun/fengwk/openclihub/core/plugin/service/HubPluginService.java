package fun.fengwk.openclihub.core.plugin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.openclihub.core.command.catalog.OpenCliCommandCatalog;
import fun.fengwk.openclihub.core.plugin.cli.OpenCliPluginCli;
import fun.fengwk.openclihub.core.plugin.repo.HubPluginSourceRepository;
import fun.fengwk.openclihub.core.plugin.service.model.HubPluginSource;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.plugin.HubInstalledPluginDTO;
import fun.fengwk.openclihub.share.model.plugin.HubPluginSourceDTO;
import fun.fengwk.openclihub.share.model.plugin.HubPluginSourceStatus;
import fun.fengwk.openclihub.share.model.plugin.HubPluginSourceUpsertDTO;
import fun.fengwk.openclihub.share.util.HubIds;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Configures OpenCLI plugin sources and synchronizes them through the official plugin CLI.
 *
 * @author fengwk
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HubPluginService {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final Pattern SOURCE_PATTERN = Pattern.compile(
        "^(github:[\\w.-]+/[\\w.-]+(?:/[\\w.-]+)?|https?://\\S+|file://\\S+|/[\\w./-]+)$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_REPOSITORY_URL_PATTERN = Pattern.compile(
        "^https?://github\\.com/([\\w.-]+)/([\\w.-]+)/?$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PLUGIN_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final List<String> OFFICIAL_EMPTY_PLUGIN_LIST_OUTPUTS = List.of(
        """
            No plugins installed.
            Install one with: opencli plugin install github:user/repo""",
        """
            No plugins installed.
              Install one with: opencli plugin install github:user/repo""");

    private final HubPluginSourceRepository repository;
    private final OpenCliPluginCli pluginCli;
    private final OpenCliCommandCatalog commandCatalog;
    private final ReentrantLock syncLock = new ReentrantLock();

    public List<HubPluginSourceDTO> listSources() {
        return repository.listAll().stream().map(this::toDTO).toList();
    }

    public HubPluginSourceDTO getSource(String id) {
        HubPluginSource source = requireSource(id);
        return toDTO(source);
    }

    public HubPluginSourceDTO createSource(HubPluginSourceUpsertDTO request) {
        HubPluginSource source = fromUpsert(null, request);
        if (repository.findByName(source.getName()) != null) {
            throw HubErrorCodes.PLUGIN_SOURCE_NAME_CONFLICT.asThrowable(
                "plugin source name already exists: " + source.getName());
        }
        source.setId(UUID.randomUUID().toString());
        source.setLastStatus(HubPluginSourceStatus.IDLE);
        source.setCreateTime(LocalDateTime.now());
        source.setVersion(0L);
        if (!repository.add(source)) {
            throw HubErrorCodes.PLUGIN_SOURCE_ARGUMENT_INVALID.asThrowable("failed to persist plugin source");
        }
        log.info("Created plugin source id={} name={} source={}", source.getId(), source.getName(), source.getSource());
        return toDTO(repository.findById(source.getId()));
    }

    public HubPluginSourceDTO updateSource(String id, HubPluginSourceUpsertDTO request) {
        HubPluginSource existing = requireSource(id);
        HubPluginSource updated = fromUpsert(existing, request);
        HubPluginSource byName = repository.findByName(updated.getName());
        if (byName != null && !byName.getId().equals(id)) {
            throw HubErrorCodes.PLUGIN_SOURCE_NAME_CONFLICT.asThrowable(
                "plugin source name already exists: " + updated.getName());
        }
        updated.setId(existing.getId());
        updated.setLastStatus(existing.getLastStatus());
        updated.setLastError(existing.getLastError());
        updated.setLastSyncedAt(existing.getLastSyncedAt());
        updated.setLastResult(existing.getLastResult());
        updated.setCreateTime(existing.getCreateTime());
        updated.setVersion(existing.getVersion());
        if (!repository.update(updated)) {
            throw HubErrorCodes.PLUGIN_SOURCE_UPDATE_CONFLICT.asThrowable(
                "plugin source changed concurrently: " + id);
        }
        log.info("Updated plugin source id={} name={} source={}", id, updated.getName(), updated.getSource());
        return toDTO(repository.findById(id));
    }

    public void deleteSource(String id) {
        requireSource(id);
        if (!repository.deleteById(id)) {
            throw HubErrorCodes.PLUGIN_SOURCE_NOT_FOUND.asThrowable("plugin source not found: " + id);
        }
        log.info("Deleted plugin source id={}", id);
    }

    public HubPluginSourceDTO syncSource(String id) {
        HubPluginSource source = requireSource(id);
        if (!source.isEnabled()) {
            throw HubErrorCodes.PLUGIN_SOURCE_ARGUMENT_INVALID.asThrowable(
                "plugin source is disabled: " + id);
        }
        if (!syncLock.tryLock()) {
            throw HubErrorCodes.PLUGIN_SYNC_BUSY.asThrowable("another plugin sync is running");
        }
        List<String> commands = new ArrayList<>();
        try {
            source.setLastStatus(HubPluginSourceStatus.SYNCING);
            source.setLastError(null);
            repository.update(source);
            log.info(
                "Plugin sync started id={} name={} source={} desiredPlugins={}",
                source.getId(),
                source.getName(),
                source.getSource(),
                source.getDesiredPlugins());

            List<String> desired = source.getDesiredPlugins();
            if (desired == null || desired.isEmpty()) {
                OpenCliPluginCli.CliResult install = pluginCli.run(List.of("install", source.getSource()));
                commands.add(summarize("install " + source.getSource(), install));
                if (install.exitCode() != 0) {
                    return failSync(source, commands, firstNonBlank(install.stderr(), install.stdout()));
                }
            } else {
                for (String pluginName : desired) {
                    String installSource = joinSourceAndPlugin(source.getSource(), pluginName);
                    // Prefer update when already present; fall back to install.
                    OpenCliPluginCli.CliResult update = pluginCli.run(List.of("update", pluginName));
                    if (update.exitCode() == 0) {
                        commands.add(summarize("update " + pluginName, update));
                        continue;
                    }
                    OpenCliPluginCli.CliResult install = pluginCli.run(List.of("install", installSource));
                    commands.add(summarize("install " + installSource, install));
                    if (install.exitCode() != 0) {
                        return failSync(source, commands, firstNonBlank(install.stderr(), install.stdout()));
                    }
                }
            }

            OpenCliPluginCli.CliResult list = pluginCli.run(List.of("list"));
            commands.add(summarize("list", list));
            commandCatalog.reload();

            HubPluginSource latest = requireSource(id);
            latest.setLastStatus(HubPluginSourceStatus.SUCCEEDED);
            latest.setLastError(null);
            latest.setLastSyncedAt(LocalDateTime.now());
            latest.setLastResult(String.join("\n", commands));
            repository.update(latest);
            log.info("Plugin sync succeeded id={} name={}", id, latest.getName());
            return toDTO(repository.findById(id));
        } catch (RuntimeException ex) {
            throw recordUnexpectedSyncFailure(source, commands, ex);
        } finally {
            syncLock.unlock();
        }
    }


    /**
     * Updates already-installed plugins through official {@code opencli plugin update}.
     * Unlike {@link #syncSource(String)}, this never runs {@code install} and therefore will not
     * silently skip an already-installed plugin that needs a tip refresh.
     *
     * <p>Plugin names:
     * <ul>
     *   <li>non-empty {@code desiredPlugins}: update exactly those names</li>
     *   <li>empty {@code desiredPlugins}: update every name returned by {@code plugin list -f json}</li>
     * </ul>
     */
    public HubPluginSourceDTO updateInstalledFromSource(String id) {
        HubPluginSource source = requireSource(id);
        if (!source.isEnabled()) {
            throw HubErrorCodes.PLUGIN_SOURCE_ARGUMENT_INVALID.asThrowable(
                "plugin source is disabled: " + id);
        }
        if (!syncLock.tryLock()) {
            throw HubErrorCodes.PLUGIN_SYNC_BUSY.asThrowable("another plugin sync is running");
        }
        List<String> commands = new ArrayList<>();
        try {
            source.setLastStatus(HubPluginSourceStatus.SYNCING);
            source.setLastError(null);
            repository.update(source);
            log.info(
                "Plugin update-installed started id={} name={} source={} desiredPlugins={}",
                source.getId(),
                source.getName(),
                source.getSource(),
                source.getDesiredPlugins());

            List<String> targets = resolveUpdateTargets(source);
            if (targets.isEmpty()) {
                return failSync(source, commands, "no installed plugins to update");
            }
            for (String pluginName : targets) {
                OpenCliPluginCli.CliResult update = pluginCli.run(List.of("update", pluginName));
                commands.add(summarize("update " + pluginName, update));
                if (update.exitCode() != 0) {
                    return failSync(source, commands, firstNonBlank(update.stderr(), update.stdout()));
                }
            }

            OpenCliPluginCli.CliResult list = pluginCli.run(List.of("list"));
            commands.add(summarize("list", list));
            commandCatalog.reload();

            HubPluginSource latest = requireSource(id);
            latest.setLastStatus(HubPluginSourceStatus.SUCCEEDED);
            latest.setLastError(null);
            latest.setLastSyncedAt(LocalDateTime.now());
            latest.setLastResult(String.join("\n", commands));
            repository.update(latest);
            log.info("Plugin update-installed succeeded id={} name={} targets={}", id, latest.getName(), targets);
            return toDTO(repository.findById(id));
        } catch (RuntimeException ex) {
            throw recordUnexpectedSyncFailure(source, commands, ex);
        } finally {
            syncLock.unlock();
        }
    }

    private List<String> resolveUpdateTargets(HubPluginSource source) {
        List<String> desired = source.getDesiredPlugins();
        if (desired != null && !desired.isEmpty()) {
            return List.copyOf(desired);
        }
        List<String> names = new ArrayList<>();
        for (HubInstalledPluginDTO installed : listInstalled()) {
            if (installed != null && installed.getName() != null && !installed.getName().isBlank()) {
                names.add(installed.getName().trim());
            }
        }
        return names;
    }

    public List<HubInstalledPluginDTO> listInstalled() {
        OpenCliPluginCli.CliResult list = pluginCli.run(List.of("list", "-f", "json"));
        if (list.exitCode() != 0) {
            throw HubErrorCodes.PLUGIN_CLI_FAILED.asThrowable(
                "opencli plugin list failed: " + firstNonBlank(list.stderr(), list.stdout()));
        }
        if (isOfficialEmptyPluginListOutput(list.stdout())) {
            return List.of();
        }
        try {
            JsonNode plugins = JSON_MAPPER.readTree(list.stdout());
            if (plugins == null || !plugins.isArray()) {
                throw new IllegalArgumentException("expected JSON array");
            }
            List<HubInstalledPluginDTO> result = new ArrayList<>();
            for (JsonNode plugin : plugins) {
                String name = text(plugin, "name");
                if (name.isBlank()) {
                    throw new IllegalArgumentException("plugin entry is missing name");
                }
                HubInstalledPluginDTO item = new HubInstalledPluginDTO();
                item.setName(name);
                item.setRaw(summarizeInstalledPlugin(plugin, name));
                result.add(item);
            }
            return result;
        } catch (Exception ex) {
            throw HubErrorCodes.PLUGIN_CLI_FAILED.asThrowable(
                ex, "Failed to parse opencli plugin list JSON");
        }
    }

    public void reloadCatalog() {
        log.info("Reloading OpenCLI command catalog after plugin maintenance");
        commandCatalog.reload();
    }

    private HubPluginSourceDTO failSync(HubPluginSource source, List<String> commands, String error) {
        HubPluginSource latest = requireSource(source.getId());
        latest.setLastStatus(HubPluginSourceStatus.FAILED);
        latest.setLastError(error == null || error.isBlank() ? "plugin sync failed" : error.trim());
        latest.setLastSyncedAt(LocalDateTime.now());
        latest.setLastResult(String.join("\n", commands));
        repository.update(latest);
        log.error(
            "Plugin sync failed id={} name={} error={}",
            latest.getId(),
            latest.getName(),
            latest.getLastError());
        throw HubErrorCodes.PLUGIN_SYNC_FAILED.asThrowable(latest.getLastError());
    }

    private RuntimeException recordUnexpectedSyncFailure(
        HubPluginSource source,
        List<String> commands,
        RuntimeException failure) {
        HubPluginSource latest = repository.findById(source.getId());
        if (latest == null || latest.getLastStatus() != HubPluginSourceStatus.SYNCING) {
            return failure;
        }

        String error = firstNonBlank(failure.getMessage(), failure.getClass().getSimpleName());
        latest.setLastStatus(HubPluginSourceStatus.FAILED);
        latest.setLastError(error);
        latest.setLastSyncedAt(LocalDateTime.now());
        latest.setLastResult(String.join("\n", commands));
        try {
            if (!repository.update(latest)) {
                log.error("Failed to persist unexpected plugin sync failure id={} error={}", source.getId(), error);
            }
        } catch (RuntimeException persistenceFailure) {
            failure.addSuppressed(persistenceFailure);
            log.error("Failed to persist unexpected plugin sync failure id={}", source.getId(), persistenceFailure);
        }
        log.error("Plugin sync failed unexpectedly id={} name={} error={}",
            source.getId(), source.getName(), error, failure);
        return HubErrorCodes.PLUGIN_SYNC_FAILED.asThrowable(failure, error);
    }

    private HubPluginSource requireSource(String id) {
        if (!HubIds.isSupported(id)) {
            throw HubErrorCodes.PLUGIN_SOURCE_NOT_FOUND.asThrowable("plugin source not found: " + id);
        }
        HubPluginSource source = repository.findById(id);
        if (source == null) {
            throw HubErrorCodes.PLUGIN_SOURCE_NOT_FOUND.asThrowable("plugin source not found: " + id);
        }
        return source;
    }

    private HubPluginSource fromUpsert(HubPluginSource existing, HubPluginSourceUpsertDTO request) {
        if (request == null) {
            throw HubErrorCodes.PLUGIN_SOURCE_ARGUMENT_INVALID.asThrowable("request is required");
        }
        String name = normalizeName(request.getName());
        String source = normalizeSource(request.getSource());
        List<String> desired = normalizeDesiredPlugins(request.getDesiredPlugins());
        for (String pluginName : desired) {
            joinSourceAndPlugin(source, pluginName);
        }

        HubPluginSource target = existing == null ? new HubPluginSource() : existing;
        target.setName(name);
        target.setSource(source);
        target.setDesiredPlugins(desired);
        target.setEnabled(request.getEnabled() == null || Boolean.TRUE.equals(request.getEnabled()));
        return target;
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw HubErrorCodes.PLUGIN_SOURCE_ARGUMENT_INVALID.asThrowable("name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 128) {
            throw HubErrorCodes.PLUGIN_SOURCE_ARGUMENT_INVALID.asThrowable("name is too long");
        }
        return trimmed;
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            throw HubErrorCodes.PLUGIN_SOURCE_ARGUMENT_INVALID.asThrowable("source is required");
        }
        String trimmed = source.trim();
        if (!SOURCE_PATTERN.matcher(trimmed).matches()) {
            throw HubErrorCodes.PLUGIN_SOURCE_ARGUMENT_INVALID.asThrowable(
                "source must be github:user/repo[/sub], https://..., file://... or an absolute path");
        }
        return trimmed;
    }

    private List<String> normalizeDesiredPlugins(List<String> desiredPlugins) {
        if (desiredPlugins == null || desiredPlugins.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String item : desiredPlugins) {
            if (item == null || item.isBlank()) {
                continue;
            }
            String name = item.trim();
            if (!PLUGIN_NAME_PATTERN.matcher(name).matches()) {
                throw HubErrorCodes.PLUGIN_SOURCE_ARGUMENT_INVALID.asThrowable(
                    "invalid desired plugin name: " + name);
            }
            normalized.add(name);
        }
        return new ArrayList<>(normalized);
    }

    /**
     * Builds the official install source for one monorepo/sub-plugin target.
     * Examples:
     * <ul>
     *   <li>{@code github:acme/plugins + weather -> github:acme/plugins/weather}</li>
     *   <li>{@code https://github.com/acme/plugins + weather -> github:acme/plugins/weather}</li>
     *   <li>{@code github:acme/plugins/weather + weather -> github:acme/plugins/weather}</li>
     * </ul>
     */
    static String joinSourceAndPlugin(String source, String pluginName) {
        if (source == null || pluginName == null || pluginName.isBlank()) {
            throw HubErrorCodes.PLUGIN_SOURCE_ARGUMENT_INVALID.asThrowable("source/plugin name is required");
        }
        String trimmedSource = source.trim();
        String trimmedPlugin = pluginName.trim();
        var githubUrlMatcher = GITHUB_REPOSITORY_URL_PATTERN.matcher(trimmedSource);
        if (githubUrlMatcher.matches()) {
            String repository = githubUrlMatcher.group(2);
            if (repository.endsWith(".git")) {
                repository = repository.substring(0, repository.length() - ".git".length());
            }
            return "github:" + githubUrlMatcher.group(1) + "/" + repository + "/" + trimmedPlugin;
        }

        if (!trimmedSource.startsWith("github:")) {
            throw HubErrorCodes.PLUGIN_SOURCE_ARGUMENT_INVALID.asThrowable(
                "desiredPlugins requires github:owner/repo or https://github.com/owner/repo source");
        }

        String[] sourceParts = trimmedSource.substring("github:".length()).split("/");
        if (sourceParts.length == 2) {
            return trimmedSource + "/" + trimmedPlugin;
        }
        if (sourceParts.length == 3 && sourceParts[2].equals(trimmedPlugin)) {
            return trimmedSource;
        }
        throw HubErrorCodes.PLUGIN_SOURCE_ARGUMENT_INVALID.asThrowable(
            "source targets a different sub-plugin: " + trimmedSource);
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private static boolean isOfficialEmptyPluginListOutput(String stdout) {
        if (stdout == null) {
            return false;
        }
        String normalized = stdout.trim().replace("\r\n", "\n").replace('\r', '\n');
        return OFFICIAL_EMPTY_PLUGIN_LIST_OUTPUTS.contains(normalized);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText().trim() : "";
    }

    private static String summarizeInstalledPlugin(JsonNode plugin, String name) {
        StringBuilder result = new StringBuilder(name);
        String version = text(plugin, "version");
        if (!version.isBlank()) {
            result.append(" @").append(version);
        }
        String description = text(plugin, "description");
        if (!description.isBlank()) {
            result.append(" — ").append(description);
        }
        JsonNode commands = plugin.get("commands");
        if (commands != null && commands.isArray()) {
            List<String> names = new ArrayList<>();
            for (JsonNode command : commands) {
                if (command.isTextual() && !command.asText().isBlank()) {
                    names.add(command.asText().trim());
                }
            }
            if (!names.isEmpty()) {
                result.append(" (").append(String.join(", ", names)).append(")");
            }
        }
        return result.toString();
    }

    private static String summarize(String action, OpenCliPluginCli.CliResult result) {
        String detail = firstNonBlank(result.stderr(), result.stdout());
        return action + " -> exit=" + result.exitCode()
            + (detail == null || detail.isBlank() ? "" : " output=" + detail.trim());
    }

    private HubPluginSourceDTO toDTO(HubPluginSource source) {
        if (source == null) {
            return null;
        }
        HubPluginSourceDTO dto = new HubPluginSourceDTO();
        dto.setId(source.getId());
        dto.setName(source.getName());
        dto.setSource(source.getSource());
        dto.setDesiredPlugins(source.getDesiredPlugins());
        dto.setEnabled(source.isEnabled());
        dto.setLastStatus(source.getLastStatus());
        dto.setLastError(source.getLastError());
        dto.setLastSyncedAt(source.getLastSyncedAt());
        dto.setLastResult(source.getLastResult());
        dto.setVersion(source.getVersion());
        return dto;
    }

}
