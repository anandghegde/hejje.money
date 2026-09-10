package money.hejje.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import money.hejje.agent.Approval;
import money.hejje.agent.ApprovalService;
import money.hejje.agent.ProposalSpec;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.Ids;
import money.hejje.common.Side;
import money.hejje.common.config.HejjeProperties;
import money.hejje.common.security.TokenCipher;
import money.hejje.common.time.HejjeClock;
import money.hejje.events.EventRisk;
import money.hejje.events.EventService;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.market.MarketService;
import money.hejje.risk.policy.PolicyAction;
import money.hejje.risk.policy.PolicyDecision;
import money.hejje.risk.policy.PolicyEngine;
import money.hejje.risk.policy.PolicyRequest;
import money.hejje.risk.policy.PolicyResult;
import money.hejje.scoring.ScoreBreakdown;
import money.hejje.scoring.ScoringService;
import money.hejje.signals.Signal;
import money.hejje.signals.SignalService;
import money.hejje.strategy.StrategyDefinition;
import money.hejje.strategy.StrategyDeployment;
import money.hejje.strategy.StrategyService;
import money.hejje.strategy.StrategyVersion;
import money.hejje.webhook.internal.WebhookStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Webhooks (plan M5.5): admin CRUD with a server-generated secret (shown once, stored encrypted), and receiving:
 * authenticate (HMAC or passphrase, timestamp window, replay store), validate, map to a strategy deployment, then create
 * the signal (AUTO decides at autonomy 4-5, otherwise an approval) or, for MANUAL_EXTERNAL, a manual order proposal.
 */
@Service
public class WebhookService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebhookStore store;
    private final TokenCipher cipher;
    private final WebhookProperties properties;
    private final HejjeClock clock;
    private final ObjectMapper json;
    private final AuditService audit;
    private final StrategyService strategies;
    private final InstrumentService instruments;
    private final MarketService market;
    private final SignalService signals;
    private final ApprovalService approvals;
    private final PolicyEngine policies;
    private final EventService events;
    private final ScoringService scoring;
    private final HejjeProperties hejje;

    WebhookService(WebhookStore store, TokenCipher cipher, WebhookProperties properties, HejjeClock clock, ObjectMapper json, AuditService audit,
            StrategyService strategies, InstrumentService instruments, MarketService market, SignalService signals, ApprovalService approvals,
            PolicyEngine policies, EventService events, ScoringService scoring, HejjeProperties hejje) {
        this.store = store;
        this.cipher = cipher;
        this.properties = properties;
        this.clock = clock;
        this.json = json;
        this.audit = audit;
        this.strategies = strategies;
        this.instruments = instruments;
        this.market = market;
        this.signals = signals;
        this.approvals = approvals;
        this.policies = policies;
        this.events = events;
        this.scoring = scoring;
        this.hejje = hejje;
    }

    /** The webhook and its secret, which is shown only here (creation and rotation). */
    public record Created(Webhook webhook, String secret) {}

    public record Receipt(int status, String result, String detail, UUID signalId, UUID approvalId) {

        static Receipt refused(int status, String result, String detail) {
            return new Receipt(status, result, detail, null, null);
        }
    }

    public List<Webhook> list() {
        return store.list();
    }

    public Webhook get(UUID id) {
        return store.find(id).orElseThrow(() -> new NoSuchElementException("No webhook " + id));
    }

    public Created create(String name, Webhook.AuthMode authMode, UUID strategyVersionId, List<String> allowedInstruments, String by) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("name: 1-64 letters, digits, '_', '.' or '-'");
        }
        if (store.nameTaken(name)) {
            throw new IllegalArgumentException("A webhook named " + name + " exists");
        }
        if (strategyVersionId != null) {
            StrategyVersion v = strategies.versionById(strategyVersionId).orElseThrow(() -> new IllegalArgumentException("No strategy version " + strategyVersionId));
            if (!v.definition().legs().isEmpty()) {
                throw new IllegalArgumentException("Option-leg strategies do not take external signals");
            }
        }
        List<String> allowed = normalize(allowedInstruments);
        Instant now = clock.now();
        String secret = newSecret();
        Webhook w = new Webhook(Ids.newId(), name, authMode == null ? Webhook.AuthMode.HMAC : authMode, strategyVersionId, true, allowed, now, by, now, null);
        store.insert(w, cipher.encrypt(secret));
        audit.record(AuditEvent.of(AuditEventType.WEBHOOK_CREATED, ActorType.USER).withActorId(by).withPayload(describe(w)));
        return new Created(w, secret);
    }

    public Webhook update(UUID id, Boolean enabled, List<String> allowedInstruments, String by) {
        Webhook w = get(id);
        boolean e = enabled == null ? w.enabled() : enabled;
        List<String> allowed = allowedInstruments == null ? w.allowedInstruments() : normalize(allowedInstruments);
        store.update(id, e, allowed, clock.now());
        Webhook updated = get(id);
        audit.record(AuditEvent.of(AuditEventType.WEBHOOK_UPDATED, ActorType.USER).withActorId(by).withPayload(describe(updated)));
        return updated;
    }

    public Created rotate(UUID id, String by) {
        Webhook w = get(id);
        String secret = newSecret();
        store.rotate(id, cipher.encrypt(secret), clock.now());
        Map<String, Object> payload = describe(w);
        payload.put("rotated", true);
        audit.record(AuditEvent.of(AuditEventType.WEBHOOK_UPDATED, ActorType.USER).withActorId(by).withPayload(payload));
        return new Created(get(id), secret);
    }

    public List<Map<String, Object>> deliveries(UUID id, int limit) {
        get(id);
        return store.deliveries(id, Math.max(1, Math.min(limit, 200)));
    }

    /** Handles one delivery; never throws for a bad request (the receipt carries the HTTP status and reason). */
    public Receipt receive(UUID id, String timestampHeader, String signatureHeader, byte[] body) {
        Optional<Webhook> found = store.find(id);
        if (found.isEmpty()) {
            return Receipt.refused(404, "UNKNOWN", "No such webhook");
        }
        Webhook w = found.get();
        Instant now = clock.now();
        String secret = cipher.decrypt(store.secretEnc(id));
        SignalIntent intent;
        try {
            intent = json.readValue(body, SignalIntent.class);
        } catch (Exception e) {
            intent = null;
        }
        String timestamp;
        String replayKey;
        if (w.authMode() == Webhook.AuthMode.HMAC) {
            if (!WebhookSignatures.verify(secret, timestampHeader, body, signatureHeader)) {
                return log(w, now, null, 401, "REJECTED", "invalid signature", body);
            }
            timestamp = timestampHeader;
            replayKey = signatureHeader.trim().toLowerCase(Locale.ROOT);
        } else {
            if (intent == null || !WebhookSignatures.constantTimeEquals(secret, intent.passphrase())) {
                return log(w, now, null, 401, "REJECTED", "invalid passphrase", null);
            }
            timestamp = intent.timestamp();
            replayKey = "sha256:" + WebhookSignatures.sha256(body);
        }
        Instant sent = WebhookSignatures.parseTimestamp(timestamp);
        if (sent == null || Duration.between(sent, now).abs().compareTo(properties.replayWindow()) > 0) {
            return log(w, now, null, 401, "REJECTED", "timestamp missing or outside ±" + properties.replayWindow().toSeconds() + "s", body);
        }
        if (store.accepted(id, replayKey)) {
            return log(w, now, null, 409, "REPLAYED", "already received", body);
        }
        if (!w.enabled()) {
            return log(w, now, null, 403, "REJECTED", "webhook disabled", body);
        }
        if (intent == null) {
            return log(w, now, null, 400, "REJECTED", "body is not a signal intent JSON", body);
        }
        Mapped mapped;
        try {
            mapped = map(w, intent);
        } catch (IllegalArgumentException e) {
            return log(w, now, null, 422, "REJECTED", e.getMessage(), body);
        }
        UUID delivery;
        try {
            delivery = store.delivery(w.id(), now, replayKey, "ACCEPTED", null, payload(body, w));
        } catch (DuplicateKeyException e) {
            return log(w, now, null, 409, "REPLAYED", "already received", body);
        }
        store.touch(w.id(), now);
        Receipt receipt;
        try {
            receipt = w.manualExternal() ? proposeManual(w, intent, mapped, replayKey) : signal(w, intent, mapped);
        } catch (RuntimeException e) {
            receipt = Receipt.refused(422, "REJECTED", e.getMessage());
        }
        store.finish(delivery, receipt.status() / 100 == 2 ? "ACCEPTED" : "REJECTED", receipt.detail(), receipt.signalId(), receipt.approvalId());
        audit(w, receipt, mapped.instrument().hejjeSymbol().format());
        return receipt;
    }

    private record Mapped(Instrument instrument, Side side, BigDecimal entry, StrategyDeployment deployment) {}

    private Mapped map(Webhook w, SignalIntent intent) {
        if (intent.instrument() == null || intent.instrument().isBlank()) {
            throw new IllegalArgumentException("instrument is required");
        }
        Instrument instrument = instruments.resolve(intent.instrument().trim()).orElseThrow(() -> new IllegalArgumentException("Unknown instrument " + intent.instrument()));
        String symbol = instrument.hejjeSymbol().format();
        if (!w.allowedInstruments().isEmpty() && !w.allowedInstruments().contains(symbol)) {
            throw new IllegalArgumentException(symbol + " is not allowed for this webhook");
        }
        Side side = side(intent.direction());
        if (intent.stop() == null) {
            throw new IllegalArgumentException("stop is required");
        }
        BigDecimal entry = intent.entry() != null ? intent.entry()
                : market.lastPrice(instrument.id()).filter(p -> p.signum() > 0).orElseThrow(() -> new IllegalArgumentException("No last price for "
                        + symbol + "; give entry"));
        boolean stopOk = side == Side.BUY ? intent.stop().compareTo(entry) < 0 : intent.stop().compareTo(entry) > 0;
        if (!stopOk) {
            throw new IllegalArgumentException("the stop " + intent.stop().toPlainString() + " is not on the losing side of the entry " + entry.toPlainString());
        }
        if (intent.target() != null && (side == Side.BUY ? intent.target().compareTo(entry) <= 0 : intent.target().compareTo(entry) >= 0)) {
            throw new IllegalArgumentException("the target " + intent.target().toPlainString() + " is not on the winning side of the entry");
        }
        if (w.manualExternal()) {
            return new Mapped(instrument, side, entry, null);
        }
        StrategyVersion version = strategies.versionById(w.strategyVersionId()).orElseThrow(() -> new IllegalArgumentException("The mapped strategy version is gone"));
        StrategyDefinition def = version.definition();
        StrategyDefinition.Direction direction = def.direction() == null ? StrategyDefinition.Direction.BOTH : def.direction();
        if (direction == StrategyDefinition.Direction.LONG && side != Side.BUY || direction == StrategyDefinition.Direction.SHORT && side != Side.SELL) {
            throw new IllegalArgumentException(def.name() + " trades " + direction + " only; " + side + " does not match");
        }
        StrategyDeployment deployment = strategies.deployments(version.id(), hejje.mode(), true).stream().filter(d -> d.pausedAt() == null)
                .filter(d -> d.instrumentIds().contains(instrument.id())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(def.name() + " v" + version.version() + " has no enabled " + hejje.mode() + " deployment on "
                        + symbol));
        if (def.tradeWindow() != null) {
            LocalTime t = clock.nowIst().toLocalTime();
            if (t.isBefore(def.tradeWindow().start()) || !t.isBefore(def.tradeWindow().end())) {
                throw new IllegalArgumentException("outside the trade window " + def.tradeWindow().start() + "-" + def.tradeWindow().end());
            }
        }
        return new Mapped(instrument, side, entry, deployment);
    }

    private Receipt signal(Webhook w, SignalIntent intent, Mapped m) {
        StrategyDeployment d = m.deployment();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", "webhook");
        evidence.put("webhookId", w.id().toString());
        evidence.put("webhook", w.name());
        if (intent.note() != null) {
            evidence.put("note", intent.note());
        }
        Signal s = signals.createExternal(d, m.instrument().id(), m.side(), m.entry(), intent.stop(), intent.target(), properties.signalValidity(),
                "webhook:" + w.name(), evidence);
        if (d.autonomyLevel() >= 4) {
            return new Receipt(202, "ACCEPTED", "signal created; autonomy " + d.autonomyLevel() + " decides through AUTO", s.id(), null);
        }
        EventRisk risk = events.risk(m.instrument().id());
        String eventLevel = risk.available() && risk.level() != null ? risk.level().name() : null;
        Integer score = scoring.latest(d.versionId(), m.instrument().id()).map(ScoreBreakdown::finalScore).orElse(null);
        PolicyResult policy = policies.decide(new PolicyRequest(PolicyAction.ORDER_NEW, ActorType.WEBHOOK, hejje.mode(), d.autonomyLevel(), eventLevel, score,
                false, d.strategyId(), m.instrument().id()));
        if (policy.decision() == PolicyDecision.DENY) {
            return new Receipt(202, "ACCEPTED", "signal created; no approval: policy " + policy.rule() + " denies (" + policy.reason() + ")", s.id(), null);
        }
        Optional<Approval> approval = approvals.proposeHeldSignal(s.id(), "webhook:" + w.name(), "WEBHOOK", policy,
                "External signal from webhook " + w.name() + (intent.note() == null ? "" : ": " + intent.note()));
        return approval.map(a -> new Receipt(202, "ACCEPTED", "signal created; approval " + a.id() + " waits in the inbox", s.id(), a.id()))
                .orElseGet(() -> new Receipt(202, "ACCEPTED", "signal created; no approval (it cannot be sized or risk would reject it)", s.id(), null));
    }

    private Receipt proposeManual(Webhook w, SignalIntent intent, Mapped m, String replayKey) {
        BigDecimal risk = intent.riskRupees() != null ? intent.riskRupees() : properties.defaultRiskRupees();
        ProposalSpec spec = new ProposalSpec(null, m.instrument().hejjeSymbol().format(), m.side().name(), risk, m.entry(), intent.stop(), intent.target(), null, null);
        Approval a = approvals.proposeExternalOrder(spec, "webhook:" + w.name(), w.id(), "webhook:" + replayKey,
                "External order intent from webhook " + w.name() + (intent.note() == null ? "" : ": " + intent.note()));
        return new Receipt(202, "ACCEPTED", "manual proposal; approval " + a.id() + " waits in the inbox", null, a.id());
    }

    private Receipt log(Webhook w, Instant now, String replayKey, int status, String result, String detail, byte[] body) {
        store.delivery(w.id(), now, replayKey, result, detail, body == null ? null : payload(body, w));
        Receipt r = Receipt.refused(status, result, detail);
        audit(w, r, null);
        return r;
    }

    private void audit(Webhook w, Receipt r, String symbol) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("webhookId", w.id().toString());
        payload.put("webhook", w.name());
        payload.put("result", r.result());
        payload.put("status", r.status());
        if (r.detail() != null) {
            payload.put("detail", r.detail());
        }
        if (symbol != null) {
            payload.put("instrument", symbol);
        }
        if (r.approvalId() != null) {
            payload.put("approvalId", r.approvalId().toString());
        }
        AuditEvent e = AuditEvent.of(AuditEventType.WEBHOOK_RECEIVED, ActorType.WEBHOOK).withActorId("webhook:" + w.name()).withPayload(payload);
        audit.record(r.signalId() == null ? e : e.withSignalId(r.signalId()));
    }

    /** The body as stored JSON, passphrase removed; null when it is not JSON. */
    private String payload(byte[] body, Webhook w) {
        try {
            var tree = json.readTree(body);
            if (tree instanceof com.fasterxml.jackson.databind.node.ObjectNode o) {
                o.remove("passphrase");
                return json.writeValueAsString(o);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Side side(String direction) {
        String d = direction == null ? "" : direction.trim().toUpperCase(Locale.ROOT);
        return switch (d) {
            case "BUY", "LONG" -> Side.BUY;
            case "SELL", "SHORT" -> Side.SELL;
            default -> throw new IllegalArgumentException("direction must be BUY or SELL (LONG/SHORT accepted)");
        };
    }

    private List<String> normalize(List<String> symbols) {
        List<String> out = new ArrayList<>();
        for (String s : symbols == null ? List.<String>of() : symbols) {
            Instrument i = instruments.resolve(s.trim()).orElseThrow(() -> new IllegalArgumentException("Unknown instrument " + s));
            out.add(i.hejjeSymbol().format());
        }
        return out;
    }

    private static Map<String, Object> describe(Webhook w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("webhookId", w.id().toString());
        m.put("name", w.name());
        m.put("authMode", w.authMode().name());
        m.put("target", w.manualExternal() ? "MANUAL_EXTERNAL" : w.strategyVersionId().toString());
        m.put("enabled", w.enabled());
        m.put("allowedInstruments", w.allowedInstruments());
        return m;
    }

    private static String newSecret() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return "whsec_" + HexFormat.of().formatHex(b);
    }
}
