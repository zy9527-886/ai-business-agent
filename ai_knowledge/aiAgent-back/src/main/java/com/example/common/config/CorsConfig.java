package com.example.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 跨域配置
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration corsConfiguration = new CorsConfiguration();
        // 允许前端页面从任意来源访问后端接口
        corsConfiguration.addAllowedOrigin("*");
        // 允许前端请求携带 token 等请求头
        corsConfiguration.addAllowedHeader("*");
        // 允许 GET、POST、PUT、DELETE 等请求方法
        corsConfiguration.addAllowedMethod("*");
        // 对所有接口路径应用跨域配置
        source.registerCorsConfiguration("/**", corsConfiguration);
        return new CorsFilter(source);
    }
}
