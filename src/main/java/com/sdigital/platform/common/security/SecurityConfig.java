package com.sdigital.platform.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * HTTP security policy for the S-Digital Platform API.
 *
 * <p>The API is a stateless OAuth2 resource server: every business request must carry a
 * bearer JWT issued by Keycloak. Only a small, explicit set of endpoints is public
 * (health probe and API documentation); everything else is denied by default so no route
 * is accidentally left anonymous.</p>
 *
 * <p>JWT decoding/validation itself lives in {@link KeycloakJwtDecoderConfig}; this class
 * only wires the filter chain that consumes the resulting {@code JwtDecoder}.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Documentation endpoints kept public so the API contract can be consulted (and tried)
     * without a token. These routes expose no business data.
     */
    private static final String[] PUBLIC_DOCUMENTATION_ENDPOINTS = {
            "/v3/api-docs",
            "/v3/api-docs.yaml",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**"
    };

    private final String allowedOrigins;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    public SecurityConfig(@Value("${sdigital.security.cors.allowed-origins:}") String allowedOrigins,
                          JwtAuthenticationConverter jwtAuthenticationConverter) {
        this.allowedOrigins = allowedOrigins;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // The API is stateless and authenticated per request with a bearer JWT,
                // so there is no session cookie for CSRF to protect. Disabling it is a
                // deliberate consequence of the stateless architecture, not a blanket choice.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // CORS preflight requests never carry credentials and must not be blocked.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Health probe consumed by the infrastructure (Docker, orchestrators).
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        // OpenAPI document and Swagger UI remain browsable without a token.
                        .requestMatchers(PUBLIC_DOCUMENTATION_ENDPOINTS).permitAll()
                        // Business API requires a valid Keycloak JWT.
                        .requestMatchers("/api/v1/**").authenticated()
                        // Explicit, secure default: no anonymous access to anything else.
                        .anyRequest().denyAll())
                // Validate incoming bearer tokens as JWTs and derive authorities from Keycloak
                // roles (realm + trusted client roles), never from OAuth2 scopes.
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(
                        jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    /**
     * CORS policy driven by configuration. Origins are listed explicitly; the wildcard
     * {@code "*"} is never used. When no origin is configured, no cross-origin request is
     * allowed (safe default for local/undecided front-ends).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(parseOrigins(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Origin"));
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private static List<String> parseOrigins(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }
}
