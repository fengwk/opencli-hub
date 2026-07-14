package fun.fengwk.openclihub.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards known client-side routes to the SPA entry point on direct navigation or refresh.
 * API, actuator and static asset paths are intentionally outside these mappings.
 *
 * @author fengwk
 */
@Controller
public class SpaForwardController {

    @GetMapping({
        "/instances",
        "/instances/{id}",
        "/executions",
        "/executions/{id}",
        "/commands",
        "/resources",
        "/settings",
        "/logs"
    })
    public String forwardToIndex() {
        return "forward:/index.html";
    }

}
