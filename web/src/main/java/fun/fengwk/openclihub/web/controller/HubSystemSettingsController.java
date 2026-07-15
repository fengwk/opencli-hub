package fun.fengwk.openclihub.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import fun.fengwk.openclihub.core.settings.service.HubSystemSettingsService;
import fun.fengwk.openclihub.core.settings.service.converter.HubSystemSettingsConverter;
import fun.fengwk.openclihub.share.model.settings.HubSystemSettingsDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Global browser proxy settings API.
 *
 * @author fengwk
 */
@RequiredArgsConstructor
@RequestMapping("/api/settings")
@RestController
public class HubSystemSettingsController {

    private final HubSystemSettingsService settingsService;
    private final HubSystemSettingsConverter converter;

    @GetMapping
    public Result<HubSystemSettingsDTO> get() {
        return Results.ok(converter.toDTO(settingsService.get()));
    }

    @PutMapping
    public Result<HubSystemSettingsDTO> update(@Valid @RequestBody HubSystemSettingsDTO request) {
        return Results.ok(converter.toDTO(settingsService.update(request)));
    }

}
