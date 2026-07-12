package fun.fengwk.openclihub.core.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.openclihub.core.converter.HubExecutionConverter;
import fun.fengwk.openclihub.core.executor.OpenCliExecutor;
import fun.fengwk.openclihub.core.model.HubExecution;
import fun.fengwk.openclihub.core.model.HubInstance;
import fun.fengwk.openclihub.core.model.OpenCliExecutionResult;
import fun.fengwk.openclihub.core.property.OpenCliHubProperties;
import fun.fengwk.openclihub.core.repo.HubExecutionRepository;
import fun.fengwk.openclihub.core.repo.HubInstanceRepository;
import fun.fengwk.openclihub.core.runtime.HubDispatchRegistry;
import fun.fengwk.openclihub.core.runtime.HubInstanceRuntimeSnapshot;
import fun.fengwk.openclihub.share.constant.HubErrorCodes;
import fun.fengwk.openclihub.share.model.HubExecutionDTO;
import fun.fengwk.openclihub.share.model.HubExecutionRequestDTO;
import fun.fengwk.openclihub.share.model.HubInstanceState;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
@Slf4j
public class HubExecutionServiceImpl implements HubExecutionService {

    private final HubExecutionConverter hubExecutionConverter;
    private final HubExecutionRepository hubExecutionRepository;
    private final HubInstanceRepository hubInstanceRepository;
    private final HubDispatchRegistry hubDispatchRegistry;
    private final OpenCliExecutor openCliExecutor;
    private final OpenCliHubProperties properties;

    @PostConstruct
    public void init() {
        hubExecutionRepository.init();
    }

    @Override
    public HubExecutionDTO execute(HubExecutionRequestDTO requestDTO) {
        String commandKey = resolveCommandKey(requestDTO);
        List<String> argv = normalizeArgv(requestDTO);
        long timeoutMillis = resolveTimeoutMillis(requestDTO);
        validateReservedArgs(argv);
        HubInstance instance = selectInstance(commandKey, requestDTO.getInstanceId());
        HubExecution execution = HubExecution.createPending(
            hubExecutionRepository.generateId(),
            instance,
            commandKey,
            argv,
            timeoutMillis);
        if (!hubExecutionRepository.add(execution)) {
            throw HubErrorCodes.EXECUTION_PERSIST_FAILED.asThrowable();
        }
        try {
            HubExecution finalExecution = hubDispatchRegistry.dispatch(instance, () -> doExecute(instance, execution));
            return hubExecutionConverter.convert(finalExecution);
        } catch (RejectedExecutionException ex) {
            log.warn("Instance queue is full, instanceCode: {}, commandKey: {}", instance.getCode(), commandKey, ex);
            throw HubErrorCodes.INSTANCE_QUEUE_FULL.asThrowable();
        }
    }

    @Override
    public HubExecutionDTO getExecution(long id) {
        return hubExecutionConverter.convert(hubExecutionRepository.findById(id));
    }

    @Override
    public Page<HubExecutionDTO> pageExecutions(fun.fengwk.convention4j.api.page.PageQuery pageQuery) {
        return hubExecutionRepository.page(pageQuery).map(hubExecutionConverter::convert);
    }

    private HubExecution doExecute(HubInstance instance, HubExecution execution) {
        execution.markRunning();
        hubExecutionRepository.update(execution);
        try {
            OpenCliExecutionResult result = openCliExecutor.execute(instance, execution.getArgv(), execution.getTimeoutMillis());
            execution.markFinished(result);
        } catch (Exception ex) {
            log.error("Execute opencli failed, instanceCode: {}, commandKey: {}", instance.getCode(), execution.getCommandKey(), ex);
            execution.markFailed(ex.getMessage());
        }
        hubExecutionRepository.update(execution);
        return execution;
    }

    private HubInstance selectInstance(String commandKey, Long instanceId) {
        if (instanceId != null) {
            HubInstance instance = hubInstanceRepository.findById(instanceId);
            validateInstance(instance, commandKey);
            return instance;
        }
        List<HubInstance> allInstances = hubInstanceRepository.listAll();
        List<HubInstance> candidates = new ArrayList<>();
        for (HubInstance instance : allInstances) {
            if (instance != null
                && instance.getState() == HubInstanceState.ONLINE
                && instance.supportsCommand(commandKey)) {
                candidates.add(instance);
            }
        }
        if (candidates.isEmpty()) {
            throw HubErrorCodes.NO_INSTANCE_AVAILABLE.asThrowable();
        }
        List<HubInstance> idleCandidates = new ArrayList<>();
        int minLoad = Integer.MAX_VALUE;
        List<HubInstance> minLoadCandidates = new ArrayList<>();
        for (HubInstance instance : candidates) {
            HubInstanceRuntimeSnapshot snapshot = hubDispatchRegistry.getSnapshot(instance.getId());
            if (snapshot.getPendingCount() >= instance.getMaxPending()) {
                continue;
            }
            if (snapshot.isIdle()) {
                idleCandidates.add(instance);
            }
            int load = snapshot.getLoad();
            if (load < minLoad) {
                minLoad = load;
                minLoadCandidates.clear();
                minLoadCandidates.add(instance);
            } else if (load == minLoad) {
                minLoadCandidates.add(instance);
            }
        }
        if (!idleCandidates.isEmpty()) {
            return pickRandom(idleCandidates);
        }
        if (!minLoadCandidates.isEmpty()) {
            return pickRandom(minLoadCandidates);
        }
        throw HubErrorCodes.INSTANCE_QUEUE_FULL.asThrowable();
    }

    private void validateInstance(HubInstance instance, String commandKey) {
        if (instance == null) {
            throw HubErrorCodes.INSTANCE_NOT_FOUND.asThrowable();
        }
        if (!instance.supportsCommand(commandKey)) {
            throw HubErrorCodes.COMMAND_NOT_SUPPORTED.asThrowable();
        }
        if (instance.getState() == HubInstanceState.OFFLINE) {
            throw HubErrorCodes.INSTANCE_OFFLINE.asThrowable();
        }
        if (instance.getState() == HubInstanceState.UNHEALTHY) {
            throw HubErrorCodes.INSTANCE_UNHEALTHY.asThrowable();
        }
        HubInstanceRuntimeSnapshot snapshot = hubDispatchRegistry.getSnapshot(instance.getId());
        if (snapshot.getPendingCount() >= instance.getMaxPending()) {
            throw HubErrorCodes.INSTANCE_QUEUE_FULL.asThrowable();
        }
    }

    private String resolveCommandKey(HubExecutionRequestDTO requestDTO) {
        if (requestDTO == null) {
            throw HubErrorCodes.INVALID_EXECUTION_REQUEST.asThrowable();
        }
        String commandKey = normalize(requestDTO.getCommandKey());
        if (commandKey != null) {
            return commandKey;
        }
        List<String> argv = requestDTO.getArgv();
        if (argv == null || argv.size() < 2) {
            throw HubErrorCodes.INVALID_EXECUTION_REQUEST.asThrowable();
        }
        String site = normalize(argv.get(0));
        String command = normalize(argv.get(1));
        if (site == null || command == null || site.startsWith("-") || command.startsWith("-")) {
            throw HubErrorCodes.INVALID_EXECUTION_REQUEST.asThrowable();
        }
        return site + '/' + command;
    }

    private List<String> normalizeArgv(HubExecutionRequestDTO requestDTO) {
        if (requestDTO == null || requestDTO.getArgv() == null || requestDTO.getArgv().isEmpty()) {
            throw HubErrorCodes.INVALID_EXECUTION_REQUEST.asThrowable();
        }
        List<String> normalized = new ArrayList<>();
        for (String arg : requestDTO.getArgv()) {
            String normalizedArg = normalize(arg);
            if (normalizedArg == null) {
                throw HubErrorCodes.INVALID_EXECUTION_REQUEST.asThrowable();
            }
            normalized.add(normalizedArg);
        }
        return normalized;
    }

    private long resolveTimeoutMillis(HubExecutionRequestDTO requestDTO) {
        long defaultTimeoutMillis = properties.getExecution().getDefaultTimeoutMillis();
        long maxTimeoutMillis = properties.getExecution().getMaxTimeoutMillis();
        long timeoutMillis = requestDTO == null || requestDTO.getTimeoutMillis() == null
            ? defaultTimeoutMillis
            : requestDTO.getTimeoutMillis();
        if (timeoutMillis <= 0 || timeoutMillis > maxTimeoutMillis) {
            throw HubErrorCodes.INVALID_EXECUTION_REQUEST.asThrowable();
        }
        return timeoutMillis;
    }

    private void validateReservedArgs(List<String> argv) {
        for (String arg : argv) {
            if (Objects.equals("--profile", arg)
                || Objects.equals("--format", arg)
                || Objects.equals("-f", arg)) {
                throw HubErrorCodes.INVALID_EXECUTION_REQUEST.asThrowable();
            }
        }
    }

    private HubInstance pickRandom(List<HubInstance> instances) {
        int index = ThreadLocalRandom.current().nextInt(instances.size());
        return instances.get(index);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
