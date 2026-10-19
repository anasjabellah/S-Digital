package com.sdigital.platform.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Writes the single JSON representation shared by every HTTP 403 denial.
 *
 * <p>Both the identity guard and the authorization filter delegate here, so an authenticated
 * caller receives the same RFC 7807 body and media type whether the refusal comes from the
 * local identity check or from a missing role. The detail is intentionally generic: no JWT
 * claim, role, user identifier or internal error is exposed.</p>
 */
public class SecurityErrorResponseWriter {

    public static final URI TYPE_FORBIDDEN = URI.create("https://s-digital.io/errors/forbidden");
    public static final String DETAIL = "Access denied";
    public static final String TITLE = "Forbidden";

    private final ObjectMapper objectMapper;

    public SecurityErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void writeForbidden(HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, DETAIL);
        problem.setTitle(TITLE);
        problem.setType(TYPE_FORBIDDEN);

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
