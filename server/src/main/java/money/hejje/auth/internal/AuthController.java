package money.hejje.auth.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.web.ApiExceptionHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    static final String REFRESH_COOKIE = "hejje_refresh";
    static final String COOKIE_PATH = "/api/v1/auth";
    static final String CLIENT_SOURCE_HEADER = "X-Client-Source";

    record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    record TokenResponse(String accessToken, String tokenType, Instant expiresAt) {}

    record Me(UUID id, String name, HejjePrincipal.Type type, Set<String> scopes) {}

    private final AuthService auth;
    private final HejjeClock clock;

    AuthController(AuthService auth, HejjeClock clock) {
        this.auth = auth;
        this.clock = clock;
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return auth.login(request.username(), request.password(), clientSource(http))
                .<ResponseEntity<?>>map(this::sessionResponse)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiExceptionHandler.problem(HttpStatus.UNAUTHORIZED, "Invalid username or password")));
    }

    @PostMapping("/refresh")
    ResponseEntity<?> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiExceptionHandler.problem(HttpStatus.UNAUTHORIZED, "Missing refresh token"));
        }
        return auth.refresh(refreshToken)
                .<ResponseEntity<?>>map(this::sessionResponse)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .header(HttpHeaders.SET_COOKIE, clearedCookie().toString())
                        .body(ApiExceptionHandler.problem(HttpStatus.UNAUTHORIZED, "Refresh token invalid, expired or revoked")));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        auth.logout(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, clearedCookie().toString()).build();
    }

    @GetMapping("/me")
    Me me(@AuthenticationPrincipal HejjePrincipal principal) {
        return new Me(principal.id(), principal.name(), principal.type(), principal.scopes());
    }

    private ResponseEntity<TokenResponse> sessionResponse(AuthService.Session session) {
        ResponseCookie cookie = cookie(session.refreshToken(), Duration.between(clock.now(), session.refreshTokenExpiresAt()));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new TokenResponse(session.accessToken(), "Bearer", session.accessTokenExpiresAt()));
    }

    private ResponseCookie clearedCookie() {
        return cookie("", Duration.ZERO);
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true).secure(true).sameSite("Strict").path(COOKIE_PATH).maxAge(maxAge).build();
    }

    private static String clientSource(HttpServletRequest http) {
        String source = http.getHeader(CLIENT_SOURCE_HEADER);
        return source == null || source.isBlank() ? "api" : source.trim();
    }
}
