package fun.fengwk.openclihub.core.model;

import fun.fengwk.openclihub.share.model.HubInstanceCreateDTO;
import fun.fengwk.openclihub.share.model.HubInstanceEditablePropertiesDTO;
import fun.fengwk.openclihub.share.model.HubInstanceState;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class HubInstance {

    private long id;
    private String code;
    private String displayName;
    private String opencliProfile;
    private String contextId;
    private String vncEndpoint;
    private HubInstanceState state;
    private int maxPending;
    private List<String> supportedCommands;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static HubInstance create(long id, HubInstanceCreateDTO createDTO) {
        HubInstance instance = new HubInstance();
        instance.setId(id);
        instance.applyEditableProperties(createDTO);
        LocalDateTime now = LocalDateTime.now();
        instance.setCreateTime(now);
        instance.setUpdateTime(now);
        return instance;
    }

    public void applyEditableProperties(HubInstanceEditablePropertiesDTO editablePropertiesDTO) {
        code = normalize(editablePropertiesDTO.getCode());
        displayName = normalize(editablePropertiesDTO.getDisplayName());
        opencliProfile = normalize(editablePropertiesDTO.getOpencliProfile());
        contextId = normalize(editablePropertiesDTO.getContextId());
        vncEndpoint = normalize(editablePropertiesDTO.getVncEndpoint());
        state = editablePropertiesDTO.getState();
        maxPending = editablePropertiesDTO.getMaxPending() == null ? 1 : editablePropertiesDTO.getMaxPending();
        supportedCommands = normalizeCommands(editablePropertiesDTO.getSupportedCommands());
        updateTime = LocalDateTime.now();
    }

    public boolean supportsCommand(String commandKey) {
        return commandKey != null && supportedCommands != null && supportedCommands.contains(commandKey);
    }

    public boolean isOnline() {
        return HubInstanceState.ONLINE == state;
    }

    public boolean isHealthy() {
        return HubInstanceState.UNHEALTHY != state;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> normalizeCommands(List<String> commands) {
        if (commands == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String command : commands) {
            String trimmed = normalize(command);
            if (trimmed != null) {
                normalized.add(trimmed);
            }
        }
        return new ArrayList<>(normalized);
    }

    public boolean sameCode(HubInstance other) {
        return other != null && Objects.equals(code, other.getCode());
    }

}
