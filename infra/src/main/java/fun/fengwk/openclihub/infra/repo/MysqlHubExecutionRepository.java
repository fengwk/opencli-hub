package fun.fengwk.openclihub.infra.repo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.idgen.NamespaceIdGenerator;
import fun.fengwk.convention4j.common.page.Pages;
import fun.fengwk.openclihub.core.model.HubExecution;
import fun.fengwk.openclihub.core.repo.HubExecutionRepository;
import fun.fengwk.openclihub.infra.mapper.HubExecutionMapper;
import fun.fengwk.openclihub.infra.model.HubExecutionDO;
import fun.fengwk.openclihub.share.model.HubExecutionStatus;
import java.io.IOException;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlHubExecutionRepository implements HubExecutionRepository {

    private final NamespaceIdGenerator<Long> idGenerator;
    private final HubExecutionMapper hubExecutionMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void init() {
        hubExecutionMapper.createTableIfNotExists();
    }

    @Override
    public long generateId() {
        return idGenerator.next(getClass());
    }

    @Override
    public boolean add(HubExecution execution) {
        return execution != null && hubExecutionMapper.insertSelective(convert(execution)) == 1;
    }

    @Override
    public boolean update(HubExecution execution) {
        return execution != null && hubExecutionMapper.updateSelectiveById(convert(execution)) == 1;
    }

    @Override
    public HubExecution findById(long id) {
        return convert(hubExecutionMapper.selectById(id));
    }

    @Override
    public Page<HubExecution> page(PageQuery pageQuery) {
        long offset = Pages.queryOffset(pageQuery);
        int limit = Pages.queryLimit(pageQuery);
        List<HubExecutionDO> result = hubExecutionMapper.pageAll(offset, limit);
        long totalCount = hubExecutionMapper.countAll();
        return Pages.page(pageQuery, result, totalCount).map(this::convert);
    }

    private HubExecutionDO convert(HubExecution execution) {
        if (execution == null) {
            return null;
        }
        HubExecutionDO executionDO = new HubExecutionDO();
        executionDO.setId(execution.getId());
        executionDO.setInstanceId(execution.getInstanceId());
        executionDO.setInstanceCode(execution.getInstanceCode());
        executionDO.setCommandKey(execution.getCommandKey());
        executionDO.setArgvJson(writeJson(execution.getArgv()));
        executionDO.setStatus(execution.getStatus() == null ? null : execution.getStatus().name());
        executionDO.setExitCode(execution.getExitCode());
        executionDO.setStdoutContent(execution.getStdout());
        executionDO.setStderrContent(execution.getStderr());
        executionDO.setErrorMessage(execution.getErrorMessage());
        executionDO.setTimeoutMillis(execution.getTimeoutMillis());
        executionDO.setQueuedAt(execution.getQueuedAt());
        executionDO.setStartedAt(execution.getStartedAt());
        executionDO.setFinishedAt(execution.getFinishedAt());
        executionDO.setCreateTime(execution.getQueuedAt());
        executionDO.setModifiedTime(execution.getFinishedAt() == null ? execution.getQueuedAt() : execution.getFinishedAt());
        return executionDO;
    }

    private HubExecution convert(HubExecutionDO executionDO) {
        if (executionDO == null) {
            return null;
        }
        HubExecution execution = new HubExecution();
        execution.setId(executionDO.getId());
        execution.setInstanceId(executionDO.getInstanceId());
        execution.setInstanceCode(executionDO.getInstanceCode());
        execution.setCommandKey(executionDO.getCommandKey());
        execution.setArgv(readStringList(executionDO.getArgvJson()));
        execution.setStatus(executionDO.getStatus() == null ? null : HubExecutionStatus.valueOf(executionDO.getStatus()));
        execution.setExitCode(executionDO.getExitCode());
        execution.setStdout(executionDO.getStdoutContent());
        execution.setStderr(executionDO.getStderrContent());
        execution.setErrorMessage(executionDO.getErrorMessage());
        execution.setTimeoutMillis(executionDO.getTimeoutMillis() == null ? 0L : executionDO.getTimeoutMillis());
        execution.setQueuedAt(executionDO.getQueuedAt());
        execution.setStartedAt(executionDO.getStartedAt());
        execution.setFinishedAt(executionDO.getFinishedAt());
        return execution;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Serialize json failed", ex);
        }
    }

    private List<String> readStringList(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (IOException ex) {
            throw new IllegalStateException("Deserialize json failed", ex);
        }
    }

}
