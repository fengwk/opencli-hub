package fun.fengwk.openclihub.core.instance.service.model;

import fun.fengwk.openclihub.share.model.instance.HubInstanceState;
import fun.fengwk.openclihub.share.model.proxy.HubProxyMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.Data;

/**
 * Persisted browser instance aggregate.
 *
 * @author fengwk
 */
@Data
public class HubInstance {

    private String id;
    private String code;
    private String displayName;
    private String contextId;
    private HubInstanceState state;
    private List<String> websites = List.of();
    private int maxPending;
    private int priority;
    private HubProxyMode proxyMode = HubProxyMode.INHERIT;
    private String proxyServer;
    private String lastErrorMessage;
    private LocalDateTime stateChangedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public void setWebsites(List<String> websites) {
        this.websites = normalizeWebsites(websites);
    }

    public boolean supportsWebsite(String website) {
        return website != null && websites.contains(website);
    }

    public boolean isRunning() {
        return HubInstanceState.RUNNING == state;
    }

    private static List<String> normalizeWebsites(List<String> websites) {
        if (websites == null || websites.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String website : websites) {
            if (website != null && !website.isBlank()) {
                normalized.add(website.trim());
            }
        }
        return new ArrayList<>(normalized);
    }

}
