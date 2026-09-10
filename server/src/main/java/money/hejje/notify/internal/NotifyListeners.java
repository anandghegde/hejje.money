package money.hejje.notify.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import money.hejje.broker.BrokerSessionChanged;
import money.hejje.broker.BrokerSessionState;
import money.hejje.common.ClientNotification;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.notify.NotificationService;
import money.hejje.notify.NotificationType;
import money.hejje.notify.NotifyProperties;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderRole;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import money.hejje.orders.OrderStateChangedEvent;
import money.hejje.orders.PositionChangedEvent;
import money.hejje.risk.KillSwitchActivated;
import money.hejje.scoring.ScoreBreakdown;
import money.hejje.scoring.ScoringService;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalGeneratedEvent;
import money.hejje.signals.SignalService;
import money.hejje.strategy.StrategyService;
import money.hejje.system.EgressIpStatus;
import money.hejje.system.EgressIpStatusChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Domain events become notifications (plan M5.5). Every listener only hands the event to one notify worker thread, so
 * the trading core never waits on a notification (and a failure here is only logged).
 */
@Component
class NotifyListeners {

    private static final Logger log = LoggerFactory.getLogger(NotifyListeners.class);

    private final NotificationService notifications;
    private final NotifyProperties properties;
    private final SignalService signals;
    private final StrategyService strategies;
    private final ScoringService scoring;
    private final OrderService orders;
    private final InstrumentService instruments;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notify-events"); // a platform thread: it blocks on JDBC
        t.setDaemon(true);
        return t;
    });

    NotifyListeners(NotificationService notifications, NotifyProperties properties, SignalService signals, StrategyService strategies, ScoringService scoring,
            OrderService orders, InstrumentService instruments) {
        this.notifications = notifications;
        this.properties = properties;
        this.signals = signals;
        this.strategies = strategies;
        this.scoring = scoring;
        this.orders = orders;
        this.instruments = instruments;
    }

    private void async(String what, Runnable r) {
        if (!properties.enabled()) {
            return;
        }
        worker.submit(() -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.warn("Notification for {} failed", what, e);
            }
        });
    }

    /** Waits until the events handed over so far are processed (tests). */
    void drain() {
        try {
            worker.submit(() -> { }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("notify worker did not drain", e);
        }
    }

    private String symbol(java.util.UUID instrumentId) {
        return instrumentId == null ? "?" : instruments.findById(instrumentId).map(Instrument::symbol).orElse(instrumentId.toString());
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onSignal(SignalGeneratedEvent event) {
        async("signal " + event.signalId(), () -> {
            Signal s = signals.find(event.signalId()).orElse(null);
            if (s == null) {
                return;
            }
            String strategy = strategies.find(s.strategyId()).map(x -> x.name()).orElse("strategy");
            String line = s.side() + " " + symbol(s.instrumentId()) + " (" + strategy + ")";
            String body = "Entry " + s.referencePrice().toPlainString() + ", stop " + s.stop().toPlainString()
                    + (s.target() == null ? "" : ", target " + s.target().toPlainString()) + "; valid until " + s.validUntil();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("signalId", s.id().toString());
            data.put("strategyId", s.strategyId().toString());
            data.put("instrumentId", s.instrumentId().toString());
            notifications.notify(NotificationType.SIGNAL_GENERATED, "Signal: " + line, body, data, "signal:" + s.id());
            Integer score = scoring.latest(s.versionId(), s.instrumentId()).map(ScoreBreakdown::finalScore).orElse(null);
            if (score != null && score >= properties.highScore()) {
                data.put("score", score);
                notifications.notify(NotificationType.HIGH_SCORE_SETUP, "High-score setup (" + score + "): " + line, body, data, "high-score:" + s.id());
            }
        });
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onOrderState(OrderStateChangedEvent event) {
        if (event.to() != OrderState.REJECTED && event.to() != OrderState.FILLED) {
            return;
        }
        async("order " + event.orderId(), () -> {
            HejjeOrder o = orders.findById(event.orderId()).orElse(null);
            if (o == null) {
                return;
            }
            String line = o.side() + " " + o.quantity() + " " + symbol(o.instrumentId()) + " (" + o.mode() + ")";
            Map<String, Object> data = Map.of("orderId", o.id().toString(), "instrumentId", o.instrumentId().toString());
            if (event.to() == OrderState.REJECTED) {
                notifications.notify(NotificationType.ORDER_REJECTED, "Order rejected: " + line, o.lastBrokerStatus() == null ? "" : o.lastBrokerStatus(), data,
                        "rejected:" + o.id());
            } else if (o.role() == OrderRole.STOP) {
                notifications.notify(NotificationType.STOP_TRIGGERED, "Stop triggered: " + line, "The protective stop order filled.", data, "stop:" + o.id());
            }
        });
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onPosition(PositionChangedEvent event) {
        if (event.netQuantity() != 0) {
            return;
        }
        async("position " + event.positionId(), () -> {
            String realized = orders.findPosition(event.positionId()).filter(p -> p.realizedPnl() != null)
                    .map(p -> "Realized P&L " + p.realizedPnl().toRupeesString()).orElse("");
            notifications.notify(NotificationType.POSITION_CLOSED, "Position closed: " + symbol(event.instrumentId()), realized,
                    Map.of("positionId", event.positionId().toString(), "instrumentId", event.instrumentId().toString()), "closed:" + event.id());
        });
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onKillSwitch(KillSwitchActivated event) {
        async("kill switch", () -> {
            Map<String, Object> data = Map.of("mode", event.mode().name(), "action", event.action().name(), "reason", String.valueOf(event.reason()));
            if ("DAILY_LOSS".equals(event.reason())) {
                notifications.notify(NotificationType.DAILY_RISK_THRESHOLD, "Daily loss limit reached (" + event.mode() + ")",
                        "The kill switch tripped on the daily loss limit; no new orders until re-armed.", data, "daily-loss:" + event.id());
            }
            notifications.notify(NotificationType.KILL_SWITCH, "Kill switch activated (" + event.mode() + ")",
                    event.action() + (event.reason() == null ? "" : ": " + event.reason()), data, "kill:" + event.id());
        });
    }

    @EventListener
    void onBroker(BrokerSessionChanged event) {
        if (event.current() == BrokerSessionState.CONNECTED || event.previous() == event.current()) {
            return;
        }
        async("broker session", () -> notifications.notify(NotificationType.BROKER_DISCONNECTED, "Broker " + event.broker() + " " + event.current(),
                event.detail() == null ? "" : event.detail(), Map.of("broker", event.broker(), "state", event.current().name()),
                "broker:" + event.broker() + ":" + event.current()));
    }

    @EventListener
    void onEgress(EgressIpStatusChanged event) {
        if (event.current() != EgressIpStatus.MISMATCH) {
            return;
        }
        async("egress ip", () -> notifications.notify(NotificationType.STATIC_IP_MISMATCH, "Static IP mismatch",
                "Observed " + event.observedIps() + ", expected " + event.expectedIps() + "; live orders are blocked.",
                Map.of("observed", event.observedIps(), "expected", event.expectedIps()), "egress:" + event.observedIps()));
    }

    @EventListener
    void onClient(ClientNotification event) {
        Map<String, Object> d = event.data();
        switch (event.type()) {
            case "drift" -> async("drift", () -> notifications.notify(NotificationType.STRATEGY_DRIFT, "Strategy drift: " + d.get("status"),
                    "Deployment " + d.get("deploymentId") + ": " + d.get("triggered"), d, "drift:" + d.get("deploymentId") + ":" + d.get("status")));
            case "approval" -> {
                if ("PENDING".equals(d.get("status"))) {
                    async("approval", () -> notifications.notify(NotificationType.APPROVAL_REQUESTED, "Approval requested: " + d.get("kind"),
                            String.valueOf(d.get("summary")), d, "approval:" + d.get("id")));
                }
            }
            case "llm_budget" -> async("llm budget", () -> notifications.notify(NotificationType.LLM_BUDGET_EXCEEDED, "LLM daily budget reached",
                    "Spent " + d.get("spentPaise") + " of " + d.get("capPaise") + " paise today; LLM features pause until tomorrow.", d,
                    "llm-budget:" + d.get("date")));
            default -> { } // "notification" (our own push) and the rest are not notifications
        }
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
