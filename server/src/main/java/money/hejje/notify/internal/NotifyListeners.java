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
    private final money.hejje.execution.ReconciliationService reconciliation;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "notify-events"); // a platform thread: it blocks on JDBC
        t.setDaemon(true);
        return t;
    });

    NotifyListeners(NotificationService notifications, NotifyProperties properties, SignalService signals, StrategyService strategies, ScoringService scoring,
            OrderService orders, InstrumentService instruments, @org.springframework.context.annotation.Lazy money.hejje.execution.ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
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
            case "market_condition" -> async("market condition", () -> notifications.notify(NotificationType.MARKET_CONDITION_CHANGED,
                    "DOWNTREND".equals(d.get("current")) ? NotificationType.Severity.WARNING : NotificationType.Severity.INFO,
                    "Market condition: " + d.get("current"), "Was " + d.get("previous") + ". " + d.get("evidence"), d,
                    "market-condition:" + d.get("date") + ":" + d.get("current")));
            case "daily_context_digest" -> async("daily context digest", () -> notifications.notify(NotificationType.DAILY_CONTEXT_DIGEST,
                    "Daily context " + d.get("date"), String.valueOf(d.get("line")), d, "daily-context:" + d.get("date")));
            case "setup" -> async("setup", () -> notifications.notify(NotificationType.valueOf(String.valueOf(d.get("event"))),
                    d.get("symbol") + ": " + String.valueOf(d.get("event")).toLowerCase().replace('_', ' '),
                    d.get("type") + " pivot " + d.get("pivot") + ", buy zone to " + d.get("buyHigh") + ", stop " + d.get("stop") + ", goal " + d.get("goal")
                            + (d.get("outcomeR") == null ? "" : ", outcome " + d.get("outcomeR") + " R") + ". Informational: Hejje does not trade it.",
                    d, "setup:" + d.get("symbol") + ":" + d.get("date")));
            case "llm_budget" -> async("llm budget", () -> notifications.notify(NotificationType.LLM_BUDGET_EXCEEDED, "LLM daily budget reached",
                    "Spent " + d.get("spentPaise") + " of " + d.get("capPaise") + " paise today; LLM features pause until tomorrow.", d,
                    "llm-budget:" + d.get("date")));
            case "swing" -> swing(d);
            default -> { } // "notification" (our own push) and the rest are not notifications
        }
    }

    /** Plan M11.6: the swing book's events (published by the swing and execution modules as {@code ClientNotification("swing")}). */
    private void swing(Map<String, Object> d) {
        String event = String.valueOf(d.get("event"));
        String symbol = d.get("symbol") != null ? String.valueOf(d.get("symbol"))
                : d.get("instrumentId") == null ? "?" : symbol(java.util.UUID.fromString(String.valueOf(d.get("instrumentId"))));
        switch (event) {
            case "GTT_PLACED" -> async("gtt placed", () -> notifications.notify(NotificationType.GTT_PLACED, symbol + ": GTT placed",
                    "Stop " + d.get("stop") + ", goal " + d.get("goal") + " for " + d.get("quantity") + " shares (GTT " + d.get("gttId") + ")", d,
                    "gtt-placed:" + d.get("gttId") + ":" + d.get("quantity")));
            case "ENTRY_FILLED" -> async("swing entry", () -> notifications.notify(NotificationType.SWING_ENTRY_FILLED, symbol + ": swing entry filled",
                    d.get("quantity") + " at " + d.get("entry") + ", stop " + d.get("stop") + ", goal " + d.get("goal"), d, "swing-entry:" + d.get("id")));
            case "STOP_HIT", "GOAL_HIT" -> {
                boolean stop = "STOP_HIT".equals(event);
                boolean gap = Boolean.TRUE.equals(d.get("gapThrough"));
                async("swing exit", () -> notifications.notify(stop ? NotificationType.SWING_STOP_HIT : NotificationType.SWING_GOAL_HIT,
                        symbol + (stop ? ": swing stop hit" : ": swing goal hit") + (gap ? " (gap-through)" : ""),
                        "Exited at " + d.get("exit") + (gap ? ", the session opened through the " + (stop ? "stop " + d.get("stop") : "goal " + d.get("goal")) : "")
                                + "; held " + d.get("holdingDays") + " sessions", d, "swing-exit:" + d.get("id")));
            }
            case "TIME_EXIT_DUE" -> async("swing time exit", () -> notifications.notify(NotificationType.SWING_TIME_EXIT_DUE, symbol + ": time exit at the next open",
                    "Held " + d.get("daysHeld") + " sessions (limit " + d.get("maxHoldingDays") + ") without reaching goal or stop", d,
                    "swing-time-exit:" + d.get("id") + ":" + d.get("date")));
            default -> { }
        }
    }

    /** Plan M11.2: a GTT issue of the swing book (missing, wrong quantity or stop, orphan) is an incident to notify. */
    @TransactionalEventListener(fallbackExecution = true)
    void onReconciliationIssue(money.hejje.execution.ReconciliationIssueEvent event) {
        if (!event.kind().startsWith("GTT_")) {
            return;
        }
        async("gtt issue", () -> {
            String detail = reconciliation.openIssues().stream().filter(i -> i.id().equals(event.issueId())).findFirst()
                    .map(i -> symbol(i.instrumentId()) + ": " + i.detail()).orElse(event.kind());
            NotificationType type = money.hejje.execution.GttService.GTT_MISSING.equals(event.kind()) ? NotificationType.GTT_MISSING : NotificationType.GTT_MISMATCH;
            notifications.notify(type, type == NotificationType.GTT_MISSING ? "Swing position unprotected" : "GTT mismatch at the broker", detail,
                    Map.of("kind", event.kind(), "issueId", event.issueId().toString()), "gtt:" + event.issueId());
        });
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
