package com.teamflow.config;

import com.teamflow.auth.SupabaseAuthService;
import com.teamflow.security.RequestFilter;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder(@Value("${teamflow.bcrypt-cost}") int cost) {
    return new BCryptPasswordEncoder(cost);
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      JdbcClient db,
      @Value("${teamflow.origin}") String origin,
      ObjectProvider<SupabaseAuthService> supabase)
      throws Exception {
    var cors = new CorsConfiguration();
    cors.setAllowedOrigins(List.of(origin));
    cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-TeamFlow-Request"));
    cors.setAllowCredentials(true);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cors);
    return http.csrf(c -> c.disable()) // Bearer API + exact Origin and custom header guard on every
        // mutation, including cookie endpoints.
        .cors(c -> c.configurationSource(source))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(c -> c.disable())
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c ->
                        c.policyDirectives(
                            "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src"
                                + " 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors"
                                + " 'none'; base-uri 'self'")))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health",
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/logout",
                        "/api/v1/auth/forgot-password",
                        "/api/v1/auth/reset-password",
                        "/api/v1/auth/verify-email",
                        "/api/v1/auth/callback",
                        "/api/v1/auth/resend-confirmation",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) ->
                            RequestFilter.problem(
                                res,
                                401,
                                "Authentication is required or has expired",
                                MDC.get("requestId")))
                    .accessDeniedHandler(
                        (req, res, ex) ->
                            RequestFilter.problem(res, 403, "Access denied", MDC.get("requestId"))))
        .addFilterBefore(
            new RequestFilter(db, origin, supabase.getIfAvailable()),
            UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
