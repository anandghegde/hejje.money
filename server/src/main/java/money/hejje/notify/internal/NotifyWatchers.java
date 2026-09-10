package money.hejje.notify.internal;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import money.hejje.common.config.HejjeProperties;
import money.hejje.events.EventRisk;
import money.hejje.events.EventRiskLevel;
import money.hejje.events.EventService;
import money.hejje.news.NewsBias;
import money.hejje.news.NewsBiasLabel;
import money.hejje.news.NewsService;
import money.hejje.notify.NotificationService;
import money.hejje.notify.NotificationType;
import money.hejje.notify.NotifyProperties;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyService;
import money.hejje.system.ExecutionReadiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Conditions without a domain event (plan M5.5), checked every minute: execution readiness turning off, a HIGH-risk
 * market event approaching a deployed instrument, and a deployed instrument's news label changing. Transitions only:
 * the first observation is the baseline. Also flushes the rate-limit digests.
 */
@Component
class NotifyWatchers {

    private static final Logger log = LoggerFactory.getLogger(NotifyWatchers.class);

    private final NotificationService notifications;
    private final NotifyProperties properties;
    private final ExecutionReadiness readiness;
    private final EventService events;
    private final NewsService news;
    private final StrategyService strategies;
    private final HejjeProperties hejje;

    private volatile Boolean executionEnabled;
    private final Set<UUID> announcedEvents = ConcurrentHashMap.newKeySet();
    private final Map<UUID, NewsBiasLabel> labels = new ConcurrentHashMap<>();

    NotifyWatchers(NotificationService notifications, NotifyProperties properties, ExecutionReadiness readiness, EventService events, NewsService news,
            StrategyService strategies, HejjeProperties hejje) {
        this.notifications = notifications;
        this.properties = properties;
        this.readiness = readiness;
        this.events = events;
        this.news = news;
        this.strategies = strategies;
        this.hejje = hejje;
    }

    @Scheduled(fixedDelayString = "${hejje.notify.digest-interval:PT5M}", initialDelayString = "PT1M")
    void digests() {
        try {
            notifications.flushDigests();
        } catch (RuntimeException e) {
            log.warn("Notification digest failed", e);
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void check() {
        if (!properties.enabled()) {
            return;
        }
        try {
            checkReadiness();
            Set<UUID> deployed = new java.util.LinkedHashSet<>();
            for (StrategyDeployment d : strategies.deployments(null, hejje.mode(), true)) {
                deployed.addAll(d.instrumentIds());
            }
            for (UUID instrumentId : deployed) {
                checkEvent(instrumentId);
                checkNews(instrumentId);
            }
        } catch (RuntimeException e) {
            log.warn("Notification watchers failed", e);
        }
    }

    void checkReadiness() {
        boolean enabled = readiness.isExecutionEnabled();
        Boolean previous = executionEnabled;
        executionEnabled = enabled;
        if (previous != null && previous && !enabled) {
            notifications.notify(NotificationType.SERVER_UNHEALTHY, "Execution readiness is off", String.join("\n", readiness.reasons()),
                    Map.of("reasons", readiness.reasons()), "unhealthy:" + readiness.reasons());
        }
    }

    void checkEvent(UUID instrumentId) {
        EventRisk risk = events.risk(instrumentId);
        if (!risk.available() || risk.level() != EventRiskLevel.HIGH || risk.nextEvent() == null || risk.minutesTo() == null) {
            return;
        }
        if (risk.minutesTo() < 0 || risk.minutesTo() > properties.eventLead().toMinutes() || !announcedEvents.add(risk.nextEvent().id())) {
            return;
        }
        notifications.notify(NotificationType.MAJOR_EVENT_APPROACHING, "In " + risk.minutesTo() + " min: " + risk.nextEvent().title(),
                String.join("; ", risk.evidence()), Map.of("eventId", risk.nextEvent().id().toString(), "instrumentId", instrumentId.toString()),
                "event:" + risk.nextEvent().id());
    }

    void checkNews(UUID instrumentId) {
        if (!news.enabled()) {
            return;
        }
        NewsBias bias = news.bias(instrumentId);
        if (!bias.available() || bias.label() == null) {
            return;
        }
        NewsBiasLabel previous = labels.put(instrumentId, bias.label());
        if (previous != null && previous != bias.label()) {
            String symbol = news.instrument(instrumentId).map(i -> i.symbol()).orElse(instrumentId.toString());
            notifications.notify(NotificationType.NEWS_CONTEXT_CHANGED, "News context " + symbol + ": " + previous + " → " + bias.label(),
                    String.join("\n", bias.evidence()), Map.of("instrumentId", instrumentId.toString(), "label", bias.label().name()),
                    "news:" + instrumentId + ":" + bias.label());
        }
    }
}
