package money.hejje.broker.internal;

import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerProperties;
import money.hejje.broker.BrokerSessionService;
import money.hejje.broker.BrokerSessionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

@RestController
@RequestMapping("/api/v1/broker")
class BrokerController {

    private static final Logger log = LoggerFactory.getLogger(BrokerController.class);

    private final BrokerSessionService sessions;
    private final BrokerProperties properties;

    BrokerController(BrokerSessionService sessions, BrokerProperties properties) {
        this.sessions = sessions;
        this.properties = properties;
    }

    @GetMapping("/login-url")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    Map<String, String> loginUrl() {
        return Map.of("broker", sessions.broker(), "loginUrl", sessions.loginUrl());
    }

    /**
     * Public: the broker redirects the user's browser here after login. Exchanges the token, then redirects to the
     * Web client. Never returns the token to the browser.
     */
    @GetMapping("/callback")
    ResponseEntity<Void> callback(@RequestParam(name = "request_token", required = false) String kiteToken,
            @RequestParam(name = "tokenId", required = false) String dhanTokenId, @RequestParam(required = false) String status) {
        String requestToken = kiteToken != null ? kiteToken : dhanTokenId; // Kite sends request_token, Dhan's consent login tokenId (M5.6)
        String target;
        if (requestToken == null || requestToken.isBlank() || (status != null && !"success".equalsIgnoreCase(status))) {
            target = properties.webUrl() + "/broker?error=" + UriUtils.encodeQueryParam("login_cancelled", StandardCharsets.UTF_8);
        } else {
            try {
                sessions.completeLogin(requestToken);
                target = properties.webUrl() + "/broker?connected=1";
            } catch (BrokerException e) {
                log.warn("Broker login failed: {} {}", e.kind(), e.brokerMessage());
                target = properties.webUrl() + "/broker?error=" + UriUtils.encodeQueryParam(e.kind().name().toLowerCase(), StandardCharsets.UTF_8);
            }
        }
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, URI.create(target).toString()).build();
    }

    /** Programmatic alternative to the browser callback (for example from the TUI after a manual login). */
    @PostMapping("/login")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    BrokerSessionStatus login(@RequestParam("request_token") @NotBlank String requestToken) {
        return sessions.completeLogin(requestToken);
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    BrokerSessionStatus status() {
        return sessions.status();
    }

    @PostMapping("/validate")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    BrokerSessionStatus validate() {
        return sessions.validate();
    }

    @PostMapping("/logout")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    BrokerSessionStatus logout() {
        return sessions.logout();
    }
}
