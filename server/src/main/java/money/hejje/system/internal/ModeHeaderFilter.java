package money.hejje.system.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import money.hejje.common.config.HejjeProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds {@code X-Hejje-Mode} to every response so clients always know the server's execution mode (PRD section 50). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ModeHeaderFilter extends OncePerRequestFilter {

    private final HejjeProperties properties;

    ModeHeaderFilter(HejjeProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Hejje-Mode", properties.mode().name());
        chain.doFilter(request, response);
    }
}
