package money.hejje.auth.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import money.hejje.common.web.ApiExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** Writes 401/403 (and 429 from the rate limiter) as {@code application/problem+json}. */
@Component
public class ProblemAuthHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper json;

    ProblemAuthHandlers(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "Insufficient scope");
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        ProblemDetail problem = ApiExceptionHandler.problem(status, detail);
        problem.setInstance(java.net.URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        json.writeValue(response.getOutputStream(), problem);
    }
}
