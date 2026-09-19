package money.hejje.auth.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the caller from {@code Authorization: Bearer <token>}: a JWT access token (user) or a
 * {@code hejje_...} API key (client). WebSocket handshakes on {@code /ws/*} pass the token as {@code ?token=};
 * there API keys are only accepted when they carry {@code market:read}.
 * An invalid or revoked token ends the request with 401 immediately.
 */
@Component
public class BearerAuthenticationFilter extends OncePerRequestFilter {

    public static final String WS_PREFIX = "/ws/";
    public static final String WS_TOKEN_PARAM = "token";

    private final TokenService tokens;
    private final ClientCredentialStore clients;
    private final AuthTime clock;
    private final ProblemAuthHandlers problems;

    BearerAuthenticationFilter(TokenService tokens, ClientCredentialStore clients, AuthTime clock, ProblemAuthHandlers problems) {
        this.tokens = tokens;
        this.clients = clients;
        this.clock = clock;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean websocket = request.getRequestURI().startsWith(WS_PREFIX);
        String token = websocket ? request.getParameter(WS_TOKEN_PARAM) : bearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token == null || SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }
        Optional<HejjePrincipal> principal = ApiKeys.looksLikeKey(token) ? authenticateApiKey(token, websocket) : tokens.verifyAccessToken(token);
        if (principal.isEmpty()) {
            problems.write(request, response, HttpStatus.UNAUTHORIZED, "Invalid or expired token");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new HejjeAuthentication(principal.get()));
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Optional<HejjePrincipal> authenticateApiKey(String token, boolean websocket) {
        return ApiKeys.parse(token)
                .flatMap(parsed -> clients.findByPrefix(parsed.prefix())
                        .filter(c -> c.isActive(clock.now()) && ApiKeys.matches(parsed, c.secretHash())))
                .filter(c -> !websocket || c.scopes().contains(ScopeCatalog.MARKET_READ))
                .map(c -> {
                    clients.touchLastUsed(c.id(), clock.now());
                    return new HejjePrincipal(c.id(), c.name(), HejjePrincipal.Type.CLIENT, c.scopes());
                });
    }

    private static String bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String value = header.substring(7).trim();
        return value.isEmpty() ? null : value;
    }
}
