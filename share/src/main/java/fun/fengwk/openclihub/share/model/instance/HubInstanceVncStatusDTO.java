package fun.fengwk.openclihub.share.model.instance;

import lombok.Data;

/**
 * Stable availability view for an Instance VNC connection.
 *
 * <p>The VNC TCP address is deliberately not exposed: clients must use the Hub WebSocket
 * endpoint rather than connecting to a runtime port directly.
 *
 * @author fengwk
 */
@Data
public class HubInstanceVncStatusDTO {

    private long instanceId;
    private boolean instanceAvailable;
    private boolean running;
    private boolean runtimeAvailable;
    private boolean vncAvailable;

}
