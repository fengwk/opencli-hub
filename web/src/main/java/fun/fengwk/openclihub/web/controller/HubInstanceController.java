package fun.fengwk.openclihub.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.openclihub.core.instance.runtime.HubInstanceLifecycleService;
import fun.fengwk.openclihub.core.instance.service.HubInstanceService;
import fun.fengwk.openclihub.core.instance.service.converter.HubInstanceConverter;
import fun.fengwk.openclihub.core.instance.service.model.HubInstance;
import fun.fengwk.openclihub.share.model.instance.HubInstanceCreateDTO;
import fun.fengwk.openclihub.share.model.instance.HubInstanceDTO;
import fun.fengwk.openclihub.share.model.instance.HubInstanceVncStatusDTO;
import fun.fengwk.openclihub.share.model.instance.HubInstanceUpdateDTO;
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
 * Instance management API.
 *
 * @author fengwk
 */
@RequiredArgsConstructor
@RequestMapping("/api/instances")
@RestController
public class HubInstanceController {

    private final HubInstanceService instanceService;
    private final HubInstanceLifecycleService lifecycleService;
    private final HubInstanceConverter converter;

    @GetMapping
    public Result<List<HubInstanceDTO>> list() {
        return Results.ok(instanceService.list().stream().map(this::toDTO).toList());
    }

    @PostMapping
    public Result<HubInstanceDTO> create(@Valid @RequestBody HubInstanceCreateDTO request) {
        return Results.created(toDTO(lifecycleService.create(request)));
    }

    @GetMapping("/{id}")
    public Result<HubInstanceDTO> get(@PathVariable long id) {
        return Results.ok(toDTO(instanceService.get(id)));
    }

    /**
     * Reports VNC availability without exposing the loopback TCP address.
     */
    @GetMapping("/{id}/vnc/status")
    public Result<HubInstanceVncStatusDTO> vncStatus(@PathVariable long id) {
        HubInstance instance = instanceService.get(id);
        var snapshot = lifecycleService.getSnapshot(id);
        boolean runtimeAvailable = snapshot.isRegistered();
        boolean vncAvailable = instance.isRunning()
            && runtimeAvailable
            && snapshot.getVncPort() != null
            && snapshot.getVncPort() > 0
            && snapshot.getVncPort() <= 65535;

        HubInstanceVncStatusDTO dto = new HubInstanceVncStatusDTO();
        dto.setInstanceId(id);
        dto.setInstanceAvailable(true);
        dto.setRunning(instance.isRunning());
        dto.setRuntimeAvailable(runtimeAvailable);
        dto.setVncAvailable(vncAvailable);
        return Results.ok(dto);
    }

    @PutMapping("/{id}")
    public Result<HubInstanceDTO> update(
        @PathVariable long id,
        @Valid @RequestBody HubInstanceUpdateDTO request) {
        return Results.ok(toDTO(instanceService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        lifecycleService.delete(id);
        return Results.ok();
    }

    @PostMapping("/{id}/start")
    public Result<HubInstanceDTO> start(@PathVariable long id) {
        return Results.ok(toDTO(lifecycleService.start(id)));
    }

    @PostMapping("/{id}/stop")
    public Result<HubInstanceDTO> stop(@PathVariable long id) {
        lifecycleService.stop(id);
        return Results.ok(toDTO(instanceService.get(id)));
    }

    @PostMapping("/{id}/restart")
    public Result<HubInstanceDTO> restart(@PathVariable long id) {
        lifecycleService.restart(id);
        return Results.ok(toDTO(instanceService.get(id)));
    }

    private HubInstanceDTO toDTO(HubInstance instance) {
        return converter.toDTO(instance, lifecycleService.getSnapshot(instance.getId()));
    }

}
