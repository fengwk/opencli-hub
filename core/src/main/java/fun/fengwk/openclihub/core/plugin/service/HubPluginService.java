package fun.fengwk.openclihub.core.plugin.service;

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

    private static final Pattern SOURCE_PATTERN = Pattern.compile(
        "^(github:[\\w.-]+/[\\w.-]+(?:/[\\w.-]+)?|https?://\\S+|file://\\S+|/[\\w./-]+)$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PLUGIN_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

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
            throw HubErrorCodes.PLUGIN_SOURCE_ARGUMENT_INVALID.asThrowable(
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

            List<String> commands = new ArrayList<>();
            List<String> desired = source.getDesiredPlugins();
            if (desired == null || desired.isEmpty()) {
                OpenCliPluginCli.CliResult install = pluginCli.run(List.of("install", source.getSource()));
                commands.add(summarize("install " + source.getSource(), install));
                if (install.exitCode() != 0) {
                    return failSync(source, commands, install.stderr());
                }
            } else {
                for (String pluginName : desired) {
                    String installSource = source.getSource().contains("/") && !source.getSource().startsWith("http")
                        && !source.getSource().startsWith("file:") && countSlashes(source.getSource()) >= 2
                        ? source.getSource()
                        : source.getSource() + "/" + pluginName;
                    // Prefer update when already present; fall back to install.
                    OpenCliPluginCli.CliResult update = pluginCli.run(List.of("update", pluginName));
                    if (update.exitCode() == 0) {
                        commands.add(summarize("update " + pluginName, update));
                        continue;
                    }
                    OpenCliPluginCli.CliResult install = pluginCli.run(List.of("install", installSource));
                    commands.add(summarize("install " + installSource, install));
                    if (install.exitCode() != 0) {
                        return failSync(source, commands, install.stderr());
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
        } finally {
            syncLock.unlock();
        }
    }

    public List<HubInstalledPluginDTO> listInstalled() {
        OpenCliPluginCli.CliResult list = pluginCli.run(List.of("list"));
        if (list.exitCode() != 0) {
            throw HubErrorCodes.PLUGIN_CLI_FAILED.asThrowable(
                "opencli plugin list failed: " + list.stderr());
        }
        List<HubInstalledPluginDTO> result = new ArrayList<>();
        for (String line : list.stdout().split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            HubInstalledPluginDTO item = new HubInstalledPluginDTO();
            item.setRaw(trimmed);
            item.setName(trimmed.split("\\s+")[0]);
            result.add(item);
        }
        return result;
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

        HubPluginSource target = existing == null ? new HubPluginSource() : existing;
        target.setName(name);
        target.setSource(source);
        target.setDesiredPlugins(desired);
        target.setEnabled(request.getEnabled() == null || Boolean.TRUE.equals(request.getEnabled()));
        target.setAutoUpdate(Boolean.TRUE.equals(request.getAutoUpdate()));
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

    private static int countSlashes(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '/') {
                count++;
            }
        }
        return count;
    }

    private static String summarize(String action, OpenCliPluginCli.CliResult result) {
        return action + " -> exit=" + result.exitCode()
            + (result.stderr() == null || result.stderr().isBlank()
                ? "" : " stderr=" + result.stderr().trim());
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
        dto.setAutoUpdate(source.isAutoUpdate());
        dto.setLastStatus(source.getLastStatus());
        dto.setLastError(source.getLastError());
        dto.setLastSyncedAt(source.getLastSyncedAt());
        dto.setLastResult(source.getLastResult());
        dto.setVersion(source.getVersion());
        return dto;
    }

}
