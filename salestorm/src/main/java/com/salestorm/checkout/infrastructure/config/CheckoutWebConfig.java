package com.salestorm.checkout.infrastructure.config;

import com.salestorm.checkout.infrastructure.security.CheckoutAuthAndRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CheckoutWebConfig implements WebMvcConfigurer {

    private final CheckoutAuthAndRateLimitInterceptor interceptor;

    public CheckoutWebConfig(CheckoutAuthAndRateLimitInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/api/v1/checkout/**");
    }
}
