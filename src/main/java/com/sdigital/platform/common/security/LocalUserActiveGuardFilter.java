package com.sdigital.platform.common.security;

import com.sdigital.platform.user.service.UserIdentityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Security guard enforcing that requests to business endpoints require a local linked and ACTIVE user.
 *
 * <p>Applied before controllers to paths under {@code /api/v1/**}. Public endpoints (health, OpenAPI,
 * Swagger, CORS preflight) are excluded.</p>
 *
 * <p>Behavior:
 * <ul>
 *   <li>OPTIONS requests pass through.</li>
 *   <li>Public endpoints (actuator/health, v3/api-docs, swagger-ui) pass through.</li>
 *   <li>If no authentication or not a JWT auth → chain continues (resource server will return 401).</li>
 *   <li>If JWT valid but no local user linked → 403 FORBIDDEN.</li>
 *   <li>If linked but INACTIVE → 403 FORBIDDEN.</li>
 *   <li>If linked and ACTIVE → chain continues.</li>
 * </ul>
 *
 * <p>This guard does not add RBAC by role; it only enforces "linked + active".</p>
 */
public class LocalUserActiveGuardFilter extends OncePerRequestFilter {

    private final UserIdentityService userIdentityService;
    private final SecurityErrorResponseWriter errorResponseWriter;

    public LocalUserActiveGuardFilter(UserIdentityService userIdentityService,
                                      SecurityErrorResponseWriter errorResponseWriter) {
        this.userIdentityService = userIdentityService;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty()) {
            path = path.substring(contextPath.length());
        }

        // Exclude CORS preflight
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        // Exclude public endpoints
        if (isPublicPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Only guard /api/v1/** paths
        if (!path.startsWith("/api/v1/")) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication instanceof JwtAuthenticationToken)) {
            // Let resource server handle 401
            filterChain.doFilter(request, response);
            return;
        }

        try {
            if (!userIdentityService.isCurrentUserActiveLinked()) {
                errorResponseWriter.writeForbidden(response);
                return;
            }
        } catch (Exception e) {
            // Fail-closed on infrastructure errors, without exposing the cause.
            errorResponseWriter.writeForbidden(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isPublicPath(String path) {
        if (path.equals("/actuator/health") || path.startsWith("/actuator/health/")) {
            return true;
        }
        if (path.equals("/v3/api-docs") || path.startsWith("/v3/api-docs/") || path.equals("/v3/api-docs.yaml")) {
            return true;
        }
        if (path.equals("/swagger-ui.html") || path.startsWith("/swagger-ui/")) {
            return true;
        }
        return false;
    }
}
