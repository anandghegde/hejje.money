package money.hejje.webhook.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.webhook.Webhook;
import money.hejje.webhook.WebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks")
class WebhookController {

    private final WebhookService webhooks;

    WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    List<Webhook> list() {
        return webhooks.list();
    }

    record CreateRequest(String name, Webhook.AuthMode authMode, UUID strategyVersionId, List<String> allowedInstruments) {}

    @PostMapping
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest body, @AuthenticationPrincipal HejjePrincipal principal) {
        WebhookService.Created c = webhooks.create(body.name(), body.authMode(), body.strategyVersionId(), body.allowedInstruments(), principal.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(created(c));
    }

    record UpdateRequest(Boolean enabled, List<String> allowedInstruments) {}

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    Webhook update(@PathVariable UUID id, @RequestBody UpdateRequest body, @AuthenticationPrincipal HejjePrincipal principal) {
        return webhooks.update(id, body.enabled(), body.allowedInstruments(), principal.name());
    }

    @PostMapping("/{id}/rotate")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    Map<String, Object> rotate(@PathVariable UUID id, @AuthenticationPrincipal HejjePrincipal principal) {
        return created(webhooks.rotate(id, principal.name()));
    }

    @GetMapping("/{id}/deliveries")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    List<Map<String, Object>> deliveries(@PathVariable UUID id, @RequestParam(defaultValue = "50") int limit) {
        return webhooks.deliveries(id, limit);
    }

    /** The public receiving endpoint: no login, authenticated by the signature or passphrase (docs/webhooks.md). */
    @PostMapping("/{id}")
    ResponseEntity<Map<String, Object>> receive(@PathVariable UUID id, @RequestHeader(value = "X-Hejje-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Hejje-Signature", required = false) String signature, @RequestBody(required = false) byte[] body) {
        WebhookService.Receipt r = webhooks.receive(id, timestamp, signature, body == null ? new byte[0] : body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", r.result());
        if (r.detail() != null) {
            out.put("detail", r.detail());
        }
        if (r.signalId() != null) {
            out.put("signalId", r.signalId());
        }
        if (r.approvalId() != null) {
            out.put("approvalId", r.approvalId());
        }
        return ResponseEntity.status(r.status()).body(out);
    }

    private static Map<String, Object> created(WebhookService.Created c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("webhook", c.webhook());
        out.put("secret", c.secret());
        out.put("url", "/api/v1/webhooks/" + c.webhook().id());
        out.put("note", "The secret is shown only now; store it in the sender. Rotate it to get a new one.");
        return out;
    }
}
