package fun.fengwk.openclihub.core.service;

import fun.fengwk.openclihub.core.converter.HubInstanceConverter;
import fun.fengwk.openclihub.core.model.HubInstance;
import fun.fengwk.openclihub.core.repo.HubInstanceRepository;
import fun.fengwk.openclihub.core.runtime.HubDispatchRegistry;
import fun.fengwk.openclihub.core.runtime.HubInstanceRuntimeSnapshot;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.HubCommandSummaryDTO;
import fun.fengwk.openclihub.share.model.HubInstanceCreateDTO;
import fun.fengwk.openclihub.share.model.HubInstanceDTO;
import fun.fengwk.openclihub.share.model.HubInstanceState;
import fun.fengwk.openclihub.share.model.HubInstanceUpdateDTO;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
public class HubInstanceServiceImpl implements HubInstanceService {

    private final HubInstanceConverter hubInstanceConverter;
    private final HubInstanceRepository hubInstanceRepository;
    private final HubDispatchRegistry hubDispatchRegistry;

    @PostConstruct
    public void init() {
        hubInstanceRepository.init();
    }

    @Override
    public HubInstanceDTO createInstance(HubInstanceCreateDTO createDTO) {
        validateCreateOrUpdate(createDTO, null);
        HubInstance instance = HubInstance.create(hubInstanceRepository.generateId(), createDTO);
        if (!hubInstanceRepository.add(instance)) {
            throw HubErrorCodes.EXECUTION_PERSIST_FAILED.asThrowable();
        }
        return convert(instance);
    }

    @Override
    public HubInstanceDTO updateInstance(long id, HubInstanceUpdateDTO updateDTO) {
        HubInstance instance = requireInstance(id);
        validateCreateOrUpdate(updateDTO, id);
        instance.applyEditableProperties(updateDTO);
        if (!hubInstanceRepository.update(instance)) {
            throw HubErrorCodes.EXECUTION_PERSIST_FAILED.asThrowable();
        }
        hubDispatchRegistry.refresh(instance);
        return convert(instance);
    }

    @Override
    public void deleteInstance(long id) {
        HubInstance instance = requireInstance(id);
        HubInstanceRuntimeSnapshot snapshot = hubDispatchRegistry.getSnapshot(id);
        if (snapshot.getLoad() > 0) {
            throw HubErrorCodes.INSTANCE_BUSY.asThrowable();
        }
        if (!hubInstanceRepository.deleteById(instance.getId())) {
            throw HubErrorCodes.INSTANCE_NOT_FOUND.asThrowable();
        }
        hubDispatchRegistry.remove(id);
    }

    @Override
    public HubInstanceDTO getInstance(long id) {
        return convert(requireInstance(id));
    }

    @Override
    public List<HubInstanceDTO> listInstances() {
        List<HubInstance> instances = new ArrayList<>(hubInstanceRepository.listAll());
        instances.sort(Comparator.comparingLong(HubInstance::getId));
        List<HubInstanceDTO> result = new ArrayList<>();
        for (HubInstance instance : instances) {
            result.add(convert(instance));
        }
        return result;
    }

    @Override
    public List<HubCommandSummaryDTO> listCommands() {
        Map<String, List<String>> commandToInstanceCodes = new LinkedHashMap<>();
        Map<String, Integer> commandToOnlineCount = new LinkedHashMap<>();
        for (HubInstance instance : hubInstanceRepository.listAll()) {
            for (String commandKey : instance.getSupportedCommands()) {
                commandToInstanceCodes.computeIfAbsent(commandKey, key -> new ArrayList<>()).add(instance.getCode());
                if (instance.getState() == HubInstanceState.ONLINE) {
                    commandToOnlineCount.merge(commandKey, 1, Integer::sum);
                }
            }
        }
        List<HubCommandSummaryDTO> result = new ArrayList<>();
        commandToInstanceCodes.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> result.add(hubInstanceConverter.convertCommandSummary(
                entry.getKey(),
                entry.getValue(),
                commandToOnlineCount.getOrDefault(entry.getKey(), 0))));
        return result;
    }

    @Override
    public HubInstanceDTO updateState(long id, HubInstanceState state) {
        if (state == null) {
            throw HubErrorCodes.INVALID_EXECUTION_REQUEST.asThrowable();
        }
        HubInstance instance = requireInstance(id);
        instance.setState(state);
        instance.setUpdateTime(java.time.LocalDateTime.now());
        if (!hubInstanceRepository.update(instance)) {
            throw HubErrorCodes.INSTANCE_NOT_FOUND.asThrowable();
        }
        return convert(instance);
    }

    private HubInstance requireInstance(long id) {
        HubInstance instance = hubInstanceRepository.findById(id);
        if (instance == null) {
            throw HubErrorCodes.INSTANCE_NOT_FOUND.asThrowable();
        }
        return instance;
    }

    private void validateCreateOrUpdate(
        fun.fengwk.openclihub.share.model.HubInstanceEditablePropertiesDTO editablePropertiesDTO,
        Long currentId) {
        if (editablePropertiesDTO == null
            || isBlank(editablePropertiesDTO.getCode())
            || isBlank(editablePropertiesDTO.getDisplayName())
            || isBlank(editablePropertiesDTO.getOpencliProfile())
            || editablePropertiesDTO.getState() == null
            || editablePropertiesDTO.getMaxPending() == null
            || editablePropertiesDTO.getMaxPending() <= 0
            || editablePropertiesDTO.getSupportedCommands() == null
            || editablePropertiesDTO.getSupportedCommands().isEmpty()) {
            throw HubErrorCodes.INVALID_EXECUTION_REQUEST.asThrowable();
        }
        HubInstance existing = hubInstanceRepository.findByCode(editablePropertiesDTO.getCode().trim());
        if (existing != null && (currentId == null || existing.getId() != currentId)) {
            throw HubErrorCodes.INSTANCE_CODE_CONFLICT.asThrowable();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private HubInstanceDTO convert(HubInstance instance) {
        return hubInstanceConverter.convert(instance, hubDispatchRegistry.getSnapshot(instance.getId()));
    }

}
