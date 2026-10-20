package com.sdigital.platform.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import com.sdigital.platform.user.service.UserIdentityService;

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
    private final UserIdentityService userIdentityService;

    public SecurityConfig(@Value("${sdigital.security.cors.allowed-origins:}") String allowedOrigins,
                          JwtAuthenticationConverter jwtAuthenticationConverter,
                          UserIdentityService userIdentityService) {
        this.allowedOrigins = allowedOrigins;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.userIdentityService = userIdentityService;
    }

    @Bean
    public SecurityErrorResponseWriter securityErrorResponseWriter(ObjectMapper objectMapper) {
        return new SecurityErrorResponseWriter(objectMapper);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityErrorResponseWriter errorResponseWriter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // CORS preflight requests never carry credentials and must not be blocked.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Health probe consumed by the infrastructure (Docker, orchestrator).
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        // OpenAPI document and Swagger UI remain browsable without a token.
                        .requestMatchers(PUBLIC_DOCUMENTATION_ENDPOINTS).permitAll()
                        // RBAC (step 3B.3): user management is restricted to business realm roles,
                        // mapped by KeycloakRolesConverter as ROLE_ADMIN / ROLE_MANAGER / ROLE_USER.
                        // Reads are shared by ADMIN and MANAGER; every write is ADMIN only.
                        // These rules are evaluated by the AuthorizationFilter, which runs *after*
                        // LocalUserActiveGuardFilter, so a missing or INACTIVE local identity is
                        // always refused first, even for an ADMIN token.
                        .requestMatchers(HttpMethod.GET, "/api/v1/users", "/api/v1/users/{id}")
                                .hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/users").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/users/{id}").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/users/{id}").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/users/{id}/activate").hasRole("ADMIN")
                        // Reference data (agencies & functions): shared read access for ADMIN and
                        // MANAGER, ADMIN-only writes, mirroring the user-management policy.
                        .requestMatchers(HttpMethod.GET, "/api/v1/agencies", "/api/v1/agencies/{id}")
                                .hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/agencies").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/agencies/{id}").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/agencies/{id}").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/agencies/{id}/activate").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/functions", "/api/v1/functions/{id}")
                                .hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/functions").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/functions/{id}").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/functions/{id}").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/functions/{id}/activate").hasRole("ADMIN")
                        // Any other business route still requires a valid JWT (and, through the
                        // guard above, a linked + ACTIVE local identity) until its own RBAC rule
                        // is defined for a route that actually exists.
                        .requestMatchers("/api/v1/**").authenticated()
                        // Explicit, secure default: no anonymous access to anything else.
                        .anyRequest().denyAll())
                // Role-based refusals reuse the same JSON body as the identity guard. Missing or
                // invalid authentication still goes through the resource-server entry point (401).
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(
                        (request, response, accessDeniedException) ->
                                errorResponseWriter.writeForbidden(response)))
                // Runs after the OAuth2 resource-server filter (BearerTokenAuthenticationFilter)
                // has populated the SecurityContext with a validated JWT, and before the
                // authorization filter. Requests without a token (or with an invalid token)
                // never reach this guard: they are rejected by the resource server with 401.
                // Only a successfully authenticated JWT identity is evaluated for the
                // local linked + ACTIVE requirement.
                .addFilterBefore(new LocalUserActiveGuardFilter(userIdentityService, errorResponseWriter),
                        AuthorizationFilter.class)
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
