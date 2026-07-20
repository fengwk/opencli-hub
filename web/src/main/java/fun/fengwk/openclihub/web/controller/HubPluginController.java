package fun.fengwk.openclihub.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.openclihub.core.plugin.service.HubPluginService;
import fun.fengwk.openclihub.share.model.plugin.HubInstalledPluginDTO;
import fun.fengwk.openclihub.share.model.plugin.HubPluginSourceDTO;
import fun.fengwk.openclihub.share.model.plugin.HubPluginSourceUpsertDTO;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Management API for OpenCLI plugin sources.
 *
 * @author fengwk
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/plugins")
public class HubPluginController {

    private final HubPluginService pluginService;

    @GetMapping("/sources")
    public Result<List<HubPluginSourceDTO>> listSources() {
        return Results.ok(pluginService.listSources());
    }

    @GetMapping("/sources/{id}")
    public Result<HubPluginSourceDTO> getSource(@PathVariable("id") String id) {
        return Results.ok(pluginService.getSource(id));
    }

    @PostMapping("/sources")
    public Result<HubPluginSourceDTO> createSource(@Valid @RequestBody HubPluginSourceUpsertDTO request) {
        return Results.ok(pluginService.createSource(request));
    }

    @PutMapping("/sources/{id}")
    public Result<HubPluginSourceDTO> updateSource(
        @PathVariable("id") String id,
        @Valid @RequestBody HubPluginSourceUpsertDTO request) {
        return Results.ok(pluginService.updateSource(id, request));
    }

    @DeleteMapping("/sources/{id}")
    public Result<Void> deleteSource(@PathVariable("id") String id) {
        pluginService.deleteSource(id);
        return Results.ok();
    }

    @PostMapping("/sources/{id}/sync")
    public Result<HubPluginSourceDTO> syncSource(@PathVariable("id") String id) {
        return Results.ok(pluginService.syncSource(id));
    }

    @PostMapping("/sources/{id}/update-installed")
    public Result<HubPluginSourceDTO> updateInstalledFromSource(@PathVariable("id") String id) {
        return Results.ok(pluginService.updateInstalledFromSource(id));
    }

    @GetMapping("/installed")
    public Result<List<HubInstalledPluginDTO>> listInstalled() {
        return Results.ok(pluginService.listInstalled());
    }

    @PostMapping("/reload-catalog")
    public Result<Void> reloadCatalog() {
        pluginService.reloadCatalog();
        return Results.ok();
    }

}
