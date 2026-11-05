package fun.fengwk.openclihub.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Closes a resource if MVC fails after the controller returns but before async starts.
 * Async transfers retain ownership until their body or callable completion closes them.
 */
@Configuration
public class HubResourceReadMvcConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                        Object handler, Exception ex) throws Exception {
                HubResourceController.closeRequestResource(request);
            }
        }).addPathPatterns("/api/resources/**");
    }
}
