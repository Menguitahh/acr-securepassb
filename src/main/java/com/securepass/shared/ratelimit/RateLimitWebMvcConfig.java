package com.securepass.shared.ratelimit;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class RateLimitWebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor interceptor;

    public RateLimitWebMvcConfig(RateLimitInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/api/v1/invitations", "/api/v1/access/validate", "/api/v1/access/sync");
    }
}
