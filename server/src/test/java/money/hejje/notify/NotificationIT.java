package money.hejje.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import money.hejje.AbstractIntegrationTest;
import money.hejje.audit.AuditService;
import money.hejje.auth.internal.ClientCredentialService;
import money.hejje.common.ClientNotification;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import money.hejje.notify.internal.NotificationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Notifications (plan M5.5): rules, severity, dedupe, the per-channel rate limit and digest, and the delivery log — on
 * the real store with a fake external channel — plus the live listeners and the REST surface.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class NotificationIT extends AbstractIntegrationTest {

    @Autowired NotificationStore store;
    @Autowired AuditService audit;
    @Autowired NotifyProperties properties;
    @Autowired HejjeClock hejjeClock;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationService live;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ClientCredentialService clients;

    /** A Telegram stand-in: 2 per minute, records what it sent, fails on demand. */
    static class FakeSender implements Channels.Sender {
        final List<Notification> sent = new CopyOnWriteArrayList<>();
        final List<List<Notification>> digests = new CopyOnWriteArrayList<>();
        volatile boolean fail;

        @Override public NotificationType.Channel channel() { return NotificationType.Channel.TELEGRAM; }
        @Override public boolean configured() { return true; }
        @Override public String status() { return "fake"; }
        @Override public int perMinute() { return 2; }

        @Override
        public void send(Notification n) {
            if (fail) {
                throw new IllegalStateException("fake channel down");
            }
            sent.add(n);
        }

        @Override
        public void sendDigest(List<Notification> held) {
            digests.add(new ArrayList<>(held));
        }
    }

    FakeSender fake;
    NotificationService service;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE notification_delivery, notification CASCADE");
        jdbc.update("UPDATE notification_rule SET enabled = TRUE, min_severity = 'INFO'");
        clock.setIst("2026-10-21T10:00:00");
        fake = new FakeSender();
        service = new NotificationService(store, () -> List.of(fake), audit, properties, hejjeClock);
    }

    @AfterEach
    void tearDown() {
        clock.set(Instant.now());
    }

    List<String> statuses(Notification n, NotificationType.Channel channel) {
        return store.deliveries(n.id()).stream().filter(d -> channel.name().equals(d.get("channel"))).map(d -> (String) d.get("status")).toList();
    }

    String ruleId(NotificationType type, NotificationType.Channel channel) {
        return service.rules().stream().filter(r -> r.eventType() == type && r.channel() == channel).findFirst().orElseThrow().id().toString();
    }

    static <T> T await(Supplier<Optional<T>> probe, String what) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Optional<T> v = probe.get();
            if (v.isPresent()) {
                return v.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    @Test
    void aboveTheRateLimitNotificationsAreHeldAndSentAsOneDigest() {
        Notification first = service.notify(NotificationType.TEST, "one", "", Map.of(), null).orElseThrow();
        Notification second = service.notify(NotificationType.TEST, "two", "", Map.of(), null).orElseThrow();
        Notification third = service.notify(NotificationType.TEST, "three", "", Map.of(), null).orElseThrow();
        service.awaitOutbox();
        assertThat(fake.sent).extracting(Notification::title).containsExactly("one", "two");
        assertThat(statuses(first, NotificationType.Channel.TELEGRAM)).containsExactly("SENT");
        assertThat(statuses(second, NotificationType.Channel.TELEGRAM)).containsExactly("SENT");
        assertThat(statuses(third, NotificationType.Channel.TELEGRAM)).containsExactly("DIGESTED");

        assertThat(service.flushDigests()).isEqualTo(1);
        assertThat(fake.digests).singleElement().satisfies(d -> assertThat(d).extracting(Notification::title).containsExactly("three"));
        assertThat(statuses(third, NotificationType.Channel.TELEGRAM)).containsExactly("DIGEST_SENT");
        assertThat(service.flushDigests()).isZero();

        clock.setIst("2026-10-21T10:01:30"); // a minute later the budget is back
        Notification fourth = service.notify(NotificationType.TEST, "four", "", Map.of(), null).orElseThrow();
        service.awaitOutbox();
        assertThat(statuses(fourth, NotificationType.Channel.TELEGRAM)).containsExactly("SENT");
    }

    @Test
    void rulesDecideTheChannelsAndRepeatsAreDeduplicated() {
        assertThat(service.notify(NotificationType.TEST, "once", "", Map.of(), "k1")).isPresent();
        assertThat(service.notify(NotificationType.TEST, "once", "", Map.of(), "k1")).isEmpty();
        assertThat(store.inbox(10)).extracting(Notification::title).containsExactly("once");

        NotificationRule warningOnly = service.updateRule(UUID.fromString(ruleId(NotificationType.TEST, NotificationType.Channel.TELEGRAM)), null,
                NotificationType.Severity.WARNING, "it");
        assertThat(warningOnly.minSeverity()).isEqualTo(NotificationType.Severity.WARNING);
        Notification info = service.notify(NotificationType.TEST, "info", "", Map.of(), null).orElseThrow();
        Notification critical = service.notify(NotificationType.TEST, NotificationType.Severity.CRITICAL, "critical", "", Map.of(), null).orElseThrow();
        service.awaitOutbox();
        assertThat(statuses(info, NotificationType.Channel.TELEGRAM)).isEmpty();
        assertThat(statuses(critical, NotificationType.Channel.TELEGRAM)).containsExactly("SENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE type = 'NOTIFICATION_RULE_UPDATED' AND payload->>'eventType' = 'TEST' "
                + "AND payload->>'minSeverity' = 'WARNING'", Long.class)).isPositive();

        service.updateRule(warningOnly.id(), false, null, "it");
        Notification off = service.notify(NotificationType.TEST, NotificationType.Severity.CRITICAL, "off", "", Map.of(), null).orElseThrow();
        assertThat(statuses(off, NotificationType.Channel.TELEGRAM)).isEmpty();
        assertThat(store.inbox(10)).extracting(Notification::title).contains("off"); // still in the inbox
    }

    @Test
    void aFailingChannelIsLoggedAndChangesNothingElse() {
        fake.fail = true;
        Notification n = service.notify(NotificationType.TEST, "down", "", Map.of(), null).orElseThrow();
        service.awaitOutbox();
        assertThat(store.deliveries(n.id())).filteredOn(d -> "TELEGRAM".equals(d.get("channel"))).singleElement()
                .satisfies(d -> assertThat(d).containsEntry("status", "FAILED").containsEntry("detail", "fake channel down"));
    }

    @Test
    void domainEventsBecomeNotificationsInTheInbox() throws Exception {
        String approvalId = UUID.randomUUID().toString();
        publisher.publishEvent(new ClientNotification("approval", Map.of("id", approvalId, "status", "PENDING", "kind", "ORDER_NEW", "summary", "BUY 10 NSE:INFY")));
        publisher.publishEvent(new ClientNotification("approval", Map.of("id", approvalId, "status", "APPROVED", "kind", "ORDER_NEW", "summary", "BUY 10 NSE:INFY")));
        publisher.publishEvent(new ClientNotification("llm_budget", Map.of("date", "2026-10-21", "spentPaise", 50100, "capPaise", 50000)));
        Notification approval = await(() -> store.inbox(50).stream().filter(n -> n.type() == NotificationType.APPROVAL_REQUESTED).findFirst(), "approval");
        assertThat(approval.body()).isEqualTo("BUY 10 NSE:INFY");
        Notification budget = await(() -> store.inbox(50).stream().filter(n -> n.type() == NotificationType.LLM_BUDGET_EXCEEDED).findFirst(), "budget");
        assertThat(budget.severity()).isEqualTo(NotificationType.Severity.WARNING);
        assertThat(store.inbox(50)).filteredOn(n -> n.type() == NotificationType.APPROVAL_REQUESTED).hasSize(1); // decisions are not requests
        // in the test profile only in-app is configured: the external channels are logged as SKIPPED with the reason
        assertThat(store.deliveries(approval.id())).extracting(d -> d.get("channel") + ":" + d.get("status"))
                .containsExactlyInAnyOrder("IN_APP:SENT", "EMAIL:SKIPPED", "TELEGRAM:SKIPPED");
    }

    @Test
    void theInboxRulesChannelsAndTestAreServedOverRest() {
        HejjePrincipal actor = new HejjePrincipal(UUID.randomUUID(), "admin", HejjePrincipal.Type.USER, ScopeCatalog.ALL);
        String admin = clients.create("notify-admin-" + UUID.randomUUID(), Set.of("admin", "market:read"), null, actor).key();
        String reader = clients.create("notify-reader-" + UUID.randomUUID(), Set.of("market:read"), null, actor).key();

        ResponseEntity<Map> test = rest.exchange("/api/v1/notifications/test", HttpMethod.POST, new HttpEntity<>(bearer(admin)), Map.class);
        assertThat(test.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) test.getBody().get("deliveries")).extracting(d -> d.get("channel") + ":" + d.get("status"))
                .containsExactlyInAnyOrder("IN_APP:SENT", "EMAIL:SKIPPED", "TELEGRAM:SKIPPED");
        String id = (String) ((Map<String, Object>) test.getBody().get("notification")).get("id");

        List<Map<String, Object>> inbox = rest.exchange("/api/v1/notifications", HttpMethod.GET, new HttpEntity<>(bearer(reader)), List.class).getBody();
        assertThat(inbox).extracting(n -> n.get("id")).contains(id);
        assertThat(rest.exchange("/api/v1/notifications/" + id + "/read", HttpMethod.POST, new HttpEntity<>(bearer(reader)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(store.find(UUID.fromString(id)).orElseThrow().readAt()).isNotNull();

        assertThat(rest.exchange("/api/v1/notifications/rules", HttpMethod.GET, new HttpEntity<>(bearer(reader)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        List<Map<String, Object>> rules = rest.exchange("/api/v1/notifications/rules", HttpMethod.GET, new HttpEntity<>(bearer(admin)), List.class).getBody();
        assertThat(rules).hasSize(16 + 13 * 2);
        String ruleId = ruleId(NotificationType.NEWS_CONTEXT_CHANGED, NotificationType.Channel.IN_APP);
        ResponseEntity<Map> updated = rest.exchange("/api/v1/notifications/rules/" + ruleId, HttpMethod.PUT,
                new HttpEntity<>(Map.of("enabled", false), bearer(admin)), Map.class);
        assertThat(updated.getBody()).containsEntry("enabled", false);

        List<Map<String, Object>> channels = rest.exchange("/api/v1/notifications/channels", HttpMethod.GET, new HttpEntity<>(bearer(admin)), List.class)
                .getBody();
        assertThat(channels).extracting(c -> c.get("channel") + ":" + c.get("configured"))
                .containsExactlyInAnyOrder("IN_APP:true", "EMAIL:false", "TELEGRAM:false");
        assertThat(channels.toString()).doesNotContain("bot-token").doesNotContain("botToken");
    }
}
