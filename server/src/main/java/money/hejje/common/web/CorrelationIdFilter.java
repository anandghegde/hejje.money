package money.hejje.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import money.hejje.common.CorrelationContext;
import money.hejje.common.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code X-Correlation-Id} (a UUID) or generates one, binds it to the MDC for the request and echoes it
 * back on the response. Non-UUID values are replaced with a fresh id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CorrelationId id = parse(request.getHeader(HEADER));
        CorrelationContext.set(id);
        response.setHeader(HEADER, id.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }

    private static CorrelationId parse(String header) {
        if (header == null || header.isBlank()) {
            return CorrelationId.newId();
        }
        try {
            return CorrelationId.of(header.trim());
        } catch (IllegalArgumentException e) {
            return CorrelationId.newId();
        }
    }
}
