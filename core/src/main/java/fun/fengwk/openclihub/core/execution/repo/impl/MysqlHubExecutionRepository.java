package fun.fengwk.openclihub.core.execution.repo.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import fun.fengwk.openclihub.core.execution.repo.HubExecutionRepository;
import fun.fengwk.openclihub.core.execution.repo.impl.mapper.HubExecutionMapper;
import fun.fengwk.openclihub.core.execution.repo.impl.model.HubExecutionDO;
import fun.fengwk.openclihub.core.execution.service.model.HubExecution;
import fun.fengwk.openclihub.share.model.execution.HubExecutionStatus;
import fun.fengwk.openclihub.share.model.execution.SiteSessionMode;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * MySQL/H2 implementation backed by the generated hub_execution mapper.
 *
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class MysqlHubExecutionRepository implements HubExecutionRepository {

    private final HubExecutionMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public String generateId() {
        return UUID.randomUUID().toString();
    }

    @Override
    public boolean add(HubExecution execution) {
        return execution != null && mapper.insert(toDO(execution)) == 1;
    }

    @Override
    public boolean update(HubExecution execution) {
        return execution != null && mapper.updateById(toDO(execution)) == 1;
    }

    @Override
    public HubExecution findById(String id) {
        return fromDO(mapper.findById(id));
    }

    @Override
    public Page<HubExecution> page(PageQuery pageQuery, String instanceId) {
        long offset = Pages.queryOffset(pageQuery);
        int limit = Pages.queryLimit(pageQuery);
        List<HubExecutionDO> rows;
        long totalCount;
        if (instanceId == null) {
            rows = mapper.pageAllOrderByQueuedAtDescIdDesc(offset, limit);
            totalCount = mapper.countAll();
        } else {
            rows = mapper.pageByInstanceIdOrderByQueuedAtDescIdDesc(instanceId, offset, limit);
            totalCount = mapper.countByInstanceId(instanceId);
        }
        return Pages.page(pageQuery, rows, totalCount).map(this::fromDO);
    }

    private HubExecutionDO toDO(HubExecution execution) {
        LocalDateTime now = LocalDateTime.now();
        HubExecutionDO target = new HubExecutionDO();
        target.setId(execution.getId());
        target.setInstanceId(execution.getInstanceId());
        target.setInstanceCode(execution.getInstanceCode());
        target.setCommandKey(execution.getCommandKey());
        target.setSite(execution.getSite());
        target.setSiteSession(execution.getSiteSession() == null ? null : execution.getSiteSession().name());
        target.setArgvJson(writeJson(execution.getArgv()));
        target.setReuseInstance(execution.isReuseInstance());
        target.setStatus(execution.getStatus() == null ? null : execution.getStatus().name());
        target.setExitCode(execution.getExitCode());
        target.setStdoutContent(execution.getStdout());
        target.setStdoutTruncated(execution.isStdoutTruncated());
        target.setStderrContent(execution.getStderr());
        target.setStderrTruncated(execution.isStderrTruncated());
        target.setErrorMessage(execution.getErrorMessage());
        target.setTimeoutMillis(execution.getTimeoutMillis());
        target.setQueuedAt(execution.getQueuedAt());
        target.setStartedAt(execution.getStartedAt());
        target.setFinishedAt(execution.getFinishedAt());
        target.setCreateTime(execution.getQueuedAt() == null ? now : execution.getQueuedAt());
        target.setModifiedTime(execution.getFinishedAt() == null ? now : execution.getFinishedAt());
        target.setVersion(0L);
        return target;
    }

    private HubExecution fromDO(HubExecutionDO source) {
        if (source == null) {
            return null;
        }
        HubExecution target = new HubExecution();
        target.setId(source.getId());
        target.setInstanceId(source.getInstanceId());
        target.setInstanceCode(source.getInstanceCode());
        target.setCommandKey(source.getCommandKey());
        target.setSite(source.getSite());
        target.setSiteSession(source.getSiteSession() == null ? null : SiteSessionMode.valueOf(source.getSiteSession()));
        target.setArgv(readStringList(source.getArgvJson()));
        target.setReuseInstance(source.isReuseInstance());
        target.setStatus(source.getStatus() == null ? null : HubExecutionStatus.valueOf(source.getStatus()));
        target.setExitCode(source.getExitCode());
        target.setStdout(source.getStdoutContent());
        target.setStdoutTruncated(Boolean.TRUE.equals(source.getStdoutTruncated()));
        target.setStderr(source.getStderrContent());
        target.setStderrTruncated(Boolean.TRUE.equals(source.getStderrTruncated()));
        target.setErrorMessage(source.getErrorMessage());
        target.setTimeoutMillis(source.getTimeoutMillis() == null ? 0L : source.getTimeoutMillis());
        target.setQueuedAt(source.getQueuedAt());
        target.setStartedAt(source.getStartedAt());
        target.setFinishedAt(source.getFinishedAt());
        return target;
    }

    private String writeJson(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize execution arguments", ex);
        }
    }

    private List<String> readStringList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to deserialize execution arguments", ex);
        }
    }

}
