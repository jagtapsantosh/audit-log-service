package com.auditlog.config.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.XXssProtectionHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private final SecurityProperties properties;
    private final JsonAuthHandlers authHandlers;
    private final boolean swaggerEnabled;

    public SecurityConfig(
            SecurityProperties properties,
            JsonAuthHandlers authHandlers,
            @Value("${springdoc.swagger-ui.enabled:true}") boolean swaggerEnabled
    ) {
        this.properties = properties;
        this.authHandlers = authHandlers;
        this.swaggerEnabled = swaggerEnabled;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        ApiKeyAuthenticationFilter apiKeyFilter = new ApiKeyAuthenticationFilter(properties, authHandlers);
        TokenEndpointRateLimitFilter rateLimitFilter = new TokenEndpointRateLimitFilter(
                properties.rateLimit().tokenPerMinute(),
                properties.rateLimit().writePerMinute(),
                authHandlers);
        RequestSizeLimitFilter sizeLimitFilter =
                new RequestSizeLimitFilter(properties.maxRequestBytes(), authHandlers);

        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .anonymous(anon -> anon.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authHandlers)
                        .accessDeniedHandler(authHandlers))
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authHandlers)
                        .accessDeniedHandler(authHandlers))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                    if (properties.localTokenEndpointEnabled()) {
                        auth.requestMatchers(HttpMethod.POST, "/auth/token").permitAll();
                    }
                    if (swaggerEnabled) {
                        auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll();
                    }
                    auth.requestMatchers(HttpMethod.POST, "/audit/events").hasAuthority("SCOPE_audit.write")
                            .requestMatchers(HttpMethod.GET, "/audit/events").hasAuthority("SCOPE_audit.read")
                            .requestMatchers(HttpMethod.GET, "/audit/export").hasAuthority("SCOPE_audit.read")
                            .requestMatchers(HttpMethod.GET, "/audit/verify")
                            .access(new JwtScopeAuthorizationManager("audit.read"))
                            .requestMatchers(HttpMethod.POST, "/audit/events/{id}/redact")
                            .access(new JwtScopeAuthorizationManager("audit.admin"))
                            .requestMatchers(HttpMethod.POST, "/audit/admin/archive")
                            .access(new JwtScopeAuthorizationManager("audit.admin"))
                            .requestMatchers("/audit/compliance/**")
                            .access(new JwtScopeAuthorizationManager("audit.compliance"))
                            .anyRequest().authenticated();
                })
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .xssProtection(xss -> xss.headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK)))
                .addFilterBefore(sizeLimitFilter, BearerTokenAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class)
                .addFilterBefore(apiKeyFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * No browser origins. This API is called by services and operator tools, not by a web app.
     * A missing {@code Access-Control-Allow-Origin} is the deny policy.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    JwtDecoder jwtDecoder() {
        if (properties.jwt() != null && properties.jwt().usesJwks()) {
            return NimbusJwtDecoder.withJwkSetUri(properties.jwt().jwkSetUri()).build();
        }
        return NimbusJwtDecoder.withSecretKey(hmacKey()).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(hmacKey()));
    }

    private SecretKey hmacKey() {
        byte[] secret = properties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        return new SecretKeySpec(secret, "HmacSHA256");
    }
}
