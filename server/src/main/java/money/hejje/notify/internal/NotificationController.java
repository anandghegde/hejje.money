package money.hejje.notify.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.notify.Notification;
import money.hejje.notify.NotificationRule;
import money.hejje.notify.NotificationService;
import money.hejje.notify.NotificationType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController {

    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    List<Notification> inbox(@RequestParam(defaultValue = "50") int limit) {
        return notifications.inbox(limit);
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("hasAuthority('SCOPE_market:read')")
    Map<String, Object> read(@PathVariable UUID id) {
        notifications.markRead(id);
        return Map.of("id", id, "read", true);
    }

    @GetMapping("/{id}/deliveries")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    List<Map<String, Object>> deliveries(@PathVariable UUID id) {
        return notifications.deliveries(id);
    }

    @GetMapping("/rules")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    List<NotificationRule> rules() {
        return notifications.rules();
    }

    record RuleUpdate(Boolean enabled, NotificationType.Severity minSeverity) {}

    @PutMapping("/rules/{id}")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    NotificationRule update(@PathVariable UUID id, @RequestBody RuleUpdate body, @AuthenticationPrincipal HejjePrincipal principal) {
        return notifications.updateRule(id, body.enabled(), body.minSeverity(), principal.name());
    }

    @GetMapping("/channels")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    List<NotificationService.ChannelStatus> channels() {
        return notifications.channels();
    }

    @PostMapping("/test")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    Map<String, Object> test(@AuthenticationPrincipal HejjePrincipal principal) {
        return notifications.test(principal.name());
    }
}
