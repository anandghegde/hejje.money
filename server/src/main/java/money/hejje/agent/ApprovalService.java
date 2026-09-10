package money.hejje.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import money.hejje.agent.internal.ApprovalStore;
import money.hejje.agent.internal.OrderProposals;
import money.hejje.audit.AuditEvent;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditService;
import money.hejje.common.ActorType;
import money.hejje.common.ClientNotification;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Ids;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.time.HejjeClock;
import money.hejje.execution.ExecutionEngine;
import money.hejje.execution.ModifyCommand;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.IntentStatus;
import money.hejje.orders.OrderIntent;
import money.hejje.orders.OrderReason;
import money.hejje.orders.OrderService;
import money.hejje.orders.Position;
import money.hejje.risk.policy.PolicyAction;
import money.hejje.risk.policy.PolicyDecision;
import money.hejje.risk.policy.PolicyEngine;
import money.hejje.risk.policy.PolicyRequest;
import money.hejje.risk.policy.PolicyResult;
import money.hejje.signals.SignalService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Agent proposals and their human approval (PRD 27 Level 3, 30, 49; plan M4.4). Agents propose (a PROPOSED intent and a
 * PENDING approval with the dry-run risk and policy decisions); a human with {@code orders:execute} approves or rejects.
 * Approval re-runs policy and risk, then sends the order through the normal pipeline (signal proposals through
 * {@link SignalService#execute}, so the strategy's stop management applies). An agent credential can never approve its
 * own proposal; expired approvals cannot be approved.
 */
@Service
public class ApprovalService {

    private final money.hejje.execution.BasketService baskets;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalStore store;
    private final OrderProposals proposals;
    private final OrderService orders;
    private final ExecutionEngine execution;
    private final SignalService signals;
    private final PolicyEngine policies;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final AgentProperties props;
    private final HejjeClock clock;
    private final ObjectMapper json;

    ApprovalService(ApprovalStore store, OrderProposals proposals, OrderService orders, ExecutionEngine execution, SignalService signals, PolicyEngine policies,
            AuditService audit, ApplicationEventPublisher events, AgentProperties props, HejjeClock clock, ObjectMapper json,
            money.hejje.execution.BasketService baskets) {
        this.baskets = baskets;
        this.store = store;
        this.proposals = proposals;
        this.orders = orders;
        this.execution = execution;
        this.signals = signals;
        this.policies = policies;
        this.audit = audit;
        this.events = events;
        this.props = props;
        this.clock = clock;
        this.json = json;
    }

    // ---- proposals (agent side)

    /** An approval for a new order; refused (DENIED/FAILED) when policy denies, risk would reject, or it cannot be sized. */
    public Approval proposeOrder(ToolContext ctx, OrderProposals.Prepared p, String rationale) {
        Optional<Approval> replay = replay(ctx);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (p.policy().decision() == PolicyDecision.DENY) {
            throw new ToolException(ToolStatus.DENIED, "Policy denies: " + p.policy().reason());
        }
        if (p.proposal().quantity() <= 0) {
            throw ToolException.failed("Cannot size the order: " + String.join("; ", p.proposal().notes()));
        }
        if (!p.risk().isApproved()) {
            throw ToolException.failed("Risk would reject this order: " + String.join("; ", p.risk().failures()));
        }
        Instant now = clock.now();
        UUID id = Ids.newId();
        Instant expires = p.signal() != null && p.signal().validUntil() != null && p.signal().validUntil().isAfter(now) ? p.signal().validUntil()
                : now.plus(props.approvals().ttl());
        var c = p.command();
        ExecutionMode mode = proposals.mode();
        OrderIntent proposed = new OrderIntent(Ids.newId(), "proposal:" + id, ctx.principal().id(), ActorType.AGENT, ctx.principal().name(), c.strategyId(),
                c.signalId(), c.instrumentId(), c.side(), c.quantity(), c.orderType(), c.product(), c.limitPrice(), c.triggerPrice(), c.stopPrice(), c.targetPrice(),
                c.maxRisk(), OrderReason.AGENT_PROPOSAL, mode, IntentStatus.PROPOSED, List.of(), now);
        orders.saveIntent(proposed);
        Approval a = new Approval(id, ApprovalKind.ORDER_NEW, ApprovalStatus.PENDING, mode.name(), proposed.id(), c.signalId(), c.strategyId(), c.instrumentId(),
                p.proposal().instrument(), null, ctx.sessionId(), ctx.principal().name(), ctx.principal().id(), ctx.principal().type().name(), ctx.idempotencyKey(),
                p.proposal().summary(), rationale, json.valueToTree(p.proposal()), json.valueToTree(p.risk()), json.valueToTree(p.policy()), now, expires, null, null,
                null, null, null);
        return recordProposal(a, ctx);
    }

    /** Principal of approvals requested by the AUTO executor for strategy signals the policy held (plan M5.2). */
    public static final UUID STRATEGY_PRINCIPAL = UUID.nameUUIDFromBytes("hejje:auto".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    /**
     * An approval for a strategy signal the AUTO policy held (plan M5.2): requested by the strategy (type STRATEGY, no
     * agent session), with the AUTO policy result and reason, expiring with the signal. One per signal; empty when the
     * signal is no longer actionable or would not size or pass risk (a human could not execute it either).
     */
    public Optional<Approval> proposeHeldSignal(UUID signalId, String requestedBy, PolicyResult policy, String reason) {
        return proposeHeldSignal(signalId, requestedBy, "STRATEGY", policy, reason);
    }

    /** {@link #proposeHeldSignal(UUID, String, PolicyResult, String)} with the requester type (STRATEGY, or WEBHOOK for external signals, M5.5). */
    public Optional<Approval> proposeHeldSignal(UUID signalId, String requestedBy, String requestedByType, PolicyResult policy, String reason) {
        String key = "held:" + signalId;
        Optional<Approval> existing = store.findByRequestKey(STRATEGY_PRINCIPAL, key);
        if (existing.isPresent()) {
            return existing;
        }
        OrderProposals.Prepared p;
        try {
            p = proposals.forHeldSignal(signalId, policy);
        } catch (ToolException e) {
            log.info("No approval for held signal {}: {}", signalId, e.getMessage());
            return Optional.empty();
        }
        if (p.proposal().quantity() <= 0 || !p.risk().isApproved()) {
            log.info("No approval for held signal {}: quantity {} / risk {}", signalId, p.proposal().quantity(), p.risk().failures());
            return Optional.empty();
        }
        Instant now = clock.now();
        UUID id = Ids.newId();
        Instant expires = p.signal().validUntil() != null && p.signal().validUntil().isAfter(now) ? p.signal().validUntil() : now.plus(props.approvals().ttl());
        var c = p.command();
        ExecutionMode mode = proposals.mode();
        OrderIntent proposed = new OrderIntent(Ids.newId(), "proposal:" + id, STRATEGY_PRINCIPAL, ActorType.STRATEGY, requestedBy, c.strategyId(), c.signalId(),
                c.instrumentId(), c.side(), c.quantity(), c.orderType(), c.product(), c.limitPrice(), c.triggerPrice(), c.stopPrice(), c.targetPrice(), c.maxRisk(),
                OrderReason.STRATEGY_SIGNAL, mode, IntentStatus.PROPOSED, List.of(), now);
        orders.saveIntent(proposed);
        Approval a = new Approval(id, ApprovalKind.ORDER_NEW, ApprovalStatus.PENDING, mode.name(), proposed.id(), signalId, c.strategyId(), c.instrumentId(),
                p.proposal().instrument(), null, null, requestedBy, STRATEGY_PRINCIPAL, requestedByType, key, p.proposal().summary(), reason,
                json.valueToTree(p.proposal()), json.valueToTree(p.risk()), json.valueToTree(policy), now, expires, null, null, null, null, null);
        store.insert(a);
        audit.record(AuditEvent.of(AuditEventType.APPROVAL_CREATED, ActorType.STRATEGY).withActorId(requestedBy).withOrderIntentId(a.intentId())
                .withStrategyId(a.strategyId()).withSignalId(signalId).withPayload(Map.of("approvalId", id.toString(), "kind", a.kind().name(),
                        "summary", a.summary(), "expiresAt", expires.toString(), "reason", reason)));
        notify(a);
        return Optional.of(a);
    }

    /**
     * A basket proposal (plan M5.3): the legs as given, policy-checked like any agent proposal (account autonomy, no
     * strategy); approving it submits the basket, whose legs then go through the normal pipeline one by one.
     */
    public Approval proposeBasket(ToolContext ctx, Map<String, Object> proposal, String summary, String rationale) {
        Optional<Approval> replay = replay(ctx);
        if (replay.isPresent()) {
            return replay.get();
        }
        ExecutionMode mode = proposals.mode();
        PolicyResult policy = policies.decide(new PolicyRequest(PolicyAction.ORDER_NEW, ActorType.AGENT, mode, props.approvals().accountAutonomyLevel(), null, null,
                false, null, null));
        if (policy.decision() == PolicyDecision.DENY) {
            throw new ToolException(ToolStatus.DENIED, "Policy denies: " + policy.reason());
        }
        Instant now = clock.now();
        Approval a = new Approval(Ids.newId(), ApprovalKind.BASKET_NEW, ApprovalStatus.PENDING, mode.name(), null, null, null, null, null, null, ctx.sessionId(),
                ctx.principal().name(), ctx.principal().id(), ctx.principal().type().name(), ctx.idempotencyKey(), summary, rationale, json.valueToTree(proposal),
                null, json.valueToTree(policy), now, now.plus(props.approvals().ttl()), null, null, null, null, null);
        return recordProposal(a, ctx);
    }

    /**
     * A manual order proposal from an external webhook (plan M5.5, MANUAL_EXTERNAL): sized and risk-checked like an agent's
     * manual proposal, requested by the webhook (type WEBHOOK, no agent session), idempotent by the webhook's replay key.
     *
     * @throws IllegalStateException when the policy denies it, it cannot be sized or risk would reject it
     */
    public Approval proposeExternalOrder(ProposalSpec spec, String requestedBy, UUID principalId, String key, String rationale) {
        Optional<Approval> existing = key == null ? Optional.empty() : store.findByRequestKey(principalId, key);
        if (existing.isPresent()) {
            return existing.get();
        }
        HejjePrincipal principal = new HejjePrincipal(principalId, requestedBy, HejjePrincipal.Type.CLIENT,
                java.util.Set.of(money.hejje.common.security.ScopeCatalog.MARKET_READ, money.hejje.common.security.ScopeCatalog.ORDERS_PREPARE));
        OrderProposals.Prepared p;
        try {
            p = proposals.prepare(spec, principal);
        } catch (ToolException e) {
            throw new IllegalStateException(e.getMessage());
        }
        if (p.policy().decision() == PolicyDecision.DENY) {
            throw new IllegalStateException("Policy denies: " + p.policy().reason());
        }
        if (p.proposal().quantity() <= 0) {
            throw new IllegalStateException("Cannot size the order: " + String.join("; ", p.proposal().notes()));
        }
        if (!p.risk().isApproved()) {
            throw new IllegalStateException("Risk would reject this order: " + String.join("; ", p.risk().failures()));
        }
        Instant now = clock.now();
        UUID id = Ids.newId();
        var c = p.command();
        ExecutionMode mode = proposals.mode();
        OrderIntent proposed = new OrderIntent(Ids.newId(), "proposal:" + id, principalId, ActorType.AGENT, requestedBy, c.strategyId(), null, c.instrumentId(),
                c.side(), c.quantity(), c.orderType(), c.product(), c.limitPrice(), c.triggerPrice(), c.stopPrice(), c.targetPrice(), c.maxRisk(),
                OrderReason.WEBHOOK, mode, IntentStatus.PROPOSED, List.of(), now);
        orders.saveIntent(proposed);
        Approval a = new Approval(id, ApprovalKind.ORDER_NEW, ApprovalStatus.PENDING, mode.name(), proposed.id(), null, c.strategyId(), c.instrumentId(),
                p.proposal().instrument(), null, null, requestedBy, principalId, "WEBHOOK", key, p.proposal().summary(), rationale, json.valueToTree(p.proposal()),
                json.valueToTree(p.risk()), json.valueToTree(p.policy()), now, now.plus(props.approvals().ttl()), null, null, null, null, null);
        store.insert(a);
        audit.record(AuditEvent.of(AuditEventType.APPROVAL_CREATED, ActorType.AGENT).withActorId(requestedBy).withOrderIntentId(a.intentId())
                .withPayload(Map.of("approvalId", id.toString(), "kind", a.kind().name(), "summary", a.summary(), "source", "webhook")));
        notify(a);
        return a;
    }

    public Approval proposeModify(ToolContext ctx, HejjeOrder order, Integer quantity, OrderType orderType, java.math.BigDecimal limitPrice,
            java.math.BigDecimal triggerPrice, String rationale) {
        ObjectNode proposal = json.createObjectNode().put("orderId", order.id().toString());
        if (quantity != null) {
            proposal.put("quantity", quantity);
        }
        if (orderType != null) {
            proposal.put("orderType", orderType.name());
        }
        if (limitPrice != null) {
            proposal.put("limitPrice", limitPrice);
        }
        if (triggerPrice != null) {
            proposal.put("triggerPrice", triggerPrice);
        }
        String symbol = proposals.symbol(order.instrumentId());
        String summary = "Modify order " + order.id().toString().substring(0, 8) + " (" + order.side() + " " + order.quantity() + " " + symbol + " "
                + order.orderType() + ")" + (quantity == null ? "" : " → quantity " + quantity) + (limitPrice == null ? "" : " → limit " + limitPrice.toPlainString())
                + (triggerPrice == null ? "" : " → trigger " + triggerPrice.toPlainString());
        return proposeAction(ctx, ApprovalKind.ORDER_MODIFY, PolicyAction.ORDER_MODIFY, order.instrumentId(), symbol, order.id(), order.intentId(), null, proposal,
                summary, rationale);
    }

    public Approval proposeCancel(ToolContext ctx, HejjeOrder order, String rationale) {
        String symbol = proposals.symbol(order.instrumentId());
        ObjectNode proposal = json.createObjectNode().put("orderId", order.id().toString());
        String summary = "Cancel order " + order.id().toString().substring(0, 8) + " (" + order.side() + " " + order.quantity() + " " + symbol + " " + order.orderType()
                + (order.limitPrice() == null ? "" : " " + order.limitPrice().toPlainString()) + ", " + order.state() + ")";
        return proposeAction(ctx, ApprovalKind.ORDER_CANCEL, PolicyAction.ORDER_CANCEL, order.instrumentId(), symbol, order.id(), order.intentId(), null, proposal,
                summary, rationale);
    }

    public Approval proposeClose(ToolContext ctx, Position position, String rationale) {
        String symbol = proposals.symbol(position.instrumentId());
        ObjectNode proposal = json.createObjectNode().put("instrumentId", position.instrumentId().toString()).put("product", position.product().name())
                .put("netQuantity", position.netQuantity());
        String summary = "Close " + symbol + " " + position.product() + " position (net " + position.netQuantity() + ")";
        return proposeAction(ctx, ApprovalKind.POSITION_CLOSE, PolicyAction.POSITION_CLOSE, position.instrumentId(), symbol, null, null, position.strategyId(),
                proposal, summary, rationale);
    }

    private Approval proposeAction(ToolContext ctx, ApprovalKind kind, PolicyAction action, UUID instrumentId, String symbol, UUID orderId, UUID intentId,
            UUID strategyId, ObjectNode proposal, String summary, String rationale) {
        Optional<Approval> replay = replay(ctx);
        if (replay.isPresent()) {
            return replay.get();
        }
        ExecutionMode mode = proposals.mode();
        PolicyResult policy = policies.decide(new PolicyRequest(action, ActorType.AGENT, mode, props.approvals().accountAutonomyLevel(),
                proposals.eventRisk(instrumentId), null, false, strategyId, instrumentId));
        if (policy.decision() == PolicyDecision.DENY) {
            throw new ToolException(ToolStatus.DENIED, "Policy denies: " + policy.reason());
        }
        Instant now = clock.now();
        Approval a = new Approval(Ids.newId(), kind, ApprovalStatus.PENDING, mode.name(), intentId, null, strategyId, instrumentId, symbol, orderId, ctx.sessionId(),
                ctx.principal().name(), ctx.principal().id(), ctx.principal().type().name(), ctx.idempotencyKey(), summary, rationale, proposal, null,
                json.valueToTree(policy), now, now.plus(props.approvals().ttl()), null, null, null, null, null);
        return recordProposal(a, ctx);
    }

    private Approval recordProposal(Approval a, ToolContext ctx) {
        store.insert(a);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", a.id().toString());
        payload.put("kind", a.kind().name());
        payload.put("summary", a.summary());
        payload.put("expiresAt", a.expiresAt().toString());
        if (a.rationale() != null) {
            payload.put("rationale", a.rationale());
        }
        audit.record(AuditEvent.of(AuditEventType.AGENT_RECOMMENDED, ActorType.AGENT).withActorId(ctx.principal().name()).withClientSource(ctx.clientSource())
                .withOrderIntentId(a.intentId()).withOrderId(a.orderId()).withStrategyId(a.strategyId()).withSignalId(a.signalId()).withPayload(payload));
        notify(a);
        return a;
    }

    private Optional<Approval> replay(ToolContext ctx) {
        return ctx.idempotencyKey() == null ? Optional.empty() : store.findByRequestKey(ctx.principal().id(), ctx.idempotencyKey());
    }

    // ---- decisions (human side)

    public List<Approval> list(ApprovalStatus status, int limit) {
        expireDue();
        return store.list(status, limit);
    }

    public Optional<Approval> find(UUID id) {
        expireDue();
        return store.find(id);
    }

    public Approval approve(UUID id, String key, HejjePrincipal approver) {
        requireKey(key);
        expireDue();
        Approval a = store.find(id).orElseThrow(() -> new ApprovalException.NotFound(id));
        if (a.status() == ApprovalStatus.APPROVED && key.equals(a.decisionKey())) {
            return a;
        }
        if (a.status() != ApprovalStatus.PENDING) {
            throw new ApprovalException.Conflict("Approval " + id + " is " + a.status() + (a.decisionNote() == null ? "" : " (" + a.decisionNote() + ")"));
        }
        if ("CLIENT".equals(a.requestedByType()) && a.requestedByPrincipal().equals(approver.id())) {
            throw new ApprovalException.Forbidden("An agent credential cannot approve its own proposal");
        }
        if (!a.mode().equals(proposals.mode().name())) {
            throw new ApprovalException.Conflict("Approval belongs to mode " + a.mode() + "; the server runs in " + proposals.mode());
        }
        OrderProposals.Revalidation check = proposals.revalidate(a);
        if (!check.ok()) {
            ObjectNode result = json.createObjectNode().put("error", check.reason());
            result.putArray("failures").addAll(check.failures().stream().map(json.getNodeFactory()::textNode).toList());
            finishFailed(a, approver, check.reason(), result);
            throw new ApprovalException.Revalidation(check.reason(), check.failures());
        }
        if (!store.claim(id, approver.name(), clock.now(), key)) {
            Approval now = store.find(id).orElseThrow();
            if (now.status() == ApprovalStatus.APPROVED && key.equals(now.decisionKey())) {
                return now;
            }
            throw new ApprovalException.Conflict("Approval " + id + " was decided concurrently (" + now.status() + ")");
        }
        audit.record(AuditEvent.of(AuditEventType.USER_APPROVED, ActorType.USER).withActorId(approver.name()).withOrderIntentId(a.intentId())
                .withOrderId(a.orderId()).withStrategyId(a.strategyId()).withSignalId(a.signalId())
                .withPayload(Map.of("approvalId", a.id().toString(), "kind", a.kind().name(), "requestedBy", a.requestedBy())));
        ObjectNode result;
        try {
            result = execute(a, key, approver);
        } catch (RuntimeException e) {
            finishFailed(a, approver, "Execution failed: " + e.getMessage(), json.createObjectNode().put("error", String.valueOf(e.getMessage())));
            throw e;
        }
        store.complete(id, result);
        if (a.intentId() != null && a.kind() == ApprovalKind.ORDER_NEW) {
            orders.findIntent(a.intentId()).ifPresent(i -> orders.updateIntentStatus(i.withStatus(IntentStatus.SUBMITTED, List.of())));
        }
        Approval done = store.find(id).orElseThrow();
        notify(done);
        return done;
    }

    private ObjectNode execute(Approval a, String key, HejjePrincipal approver) {
        ObjectNode result = json.createObjectNode();
        switch (a.kind()) {
            case ORDER_NEW -> {
                if (a.signalId() != null && signals.isOptions(a.signalId())) {
                    money.hejje.options.OptionsPosition p = signals.executeOptions(a.signalId(), key, approver);
                    result.put("optionsPositionId", p.id().toString()).put("basketId", p.basketId().toString()).put("state", p.status().name());
                    break;
                }
                HejjeOrder order = a.signalId() != null ? signals.execute(a.signalId(), key, approver)
                        : execution.submit(proposals.command(a, approver.id(), key, ActorType.USER, approver.name()));
                result.put("orderId", order.id().toString()).put("executedIntentId", order.intentId().toString()).put("state", order.state().name());
            }
            case ORDER_MODIFY -> {
                JsonNode p = a.proposal();
                HejjeOrder order = execution.modify(a.orderId(), new ModifyCommand(p.hasNonNull("quantity") ? Quantity.of(p.get("quantity").asInt()) : null,
                        p.hasNonNull("orderType") ? OrderType.valueOf(p.get("orderType").asText()) : null,
                        p.hasNonNull("limitPrice") ? Price.of(p.get("limitPrice").decimalValue().setScale(2, java.math.RoundingMode.HALF_UP)) : null,
                        p.hasNonNull("triggerPrice") ? Price.of(p.get("triggerPrice").decimalValue().setScale(2, java.math.RoundingMode.HALF_UP)) : null));
                result.put("orderId", order.id().toString()).put("state", order.state().name());
            }
            case ORDER_CANCEL -> {
                HejjeOrder order = execution.cancel(a.orderId());
                result.put("orderId", order.id().toString()).put("state", order.state().name());
            }
            case BASKET_NEW -> {
                JsonNode p = a.proposal();
                java.util.List<money.hejje.execution.BasketCommand.Leg> legs = new java.util.ArrayList<>();
                for (JsonNode l : p.path("legs")) {
                    legs.add(new money.hejje.execution.BasketCommand.Leg(UUID.fromString(l.path("instrumentId").asText()), money.hejje.common.Side.valueOf(l.path("side").asText()),
                            l.path("quantity").asInt(), OrderType.valueOf(l.path("orderType").asText("MARKET")), Product.valueOf(l.path("product").asText("MIS")),
                            priceOf(l, "limitPrice"), null, priceOf(l, "stopPrice"), priceOf(l, "targetPrice"), l.path("hedgeFirst").asBoolean(false)));
                }
                money.hejje.execution.Basket basket = baskets.submit(new money.hejje.execution.BasketCommand(approver.id(), key, ActorType.USER, approver.name(),
                        p.path("name").asText(null), money.hejje.execution.Basket.Policy.valueOf(p.path("policy").asText("ALL_OR_NOTHING")),
                        money.hejje.execution.Basket.Rollback.valueOf(p.path("rollback").asText("NONE")),
                        p.hasNonNull("deadlineMinutes") ? java.time.Duration.ofMinutes(p.get("deadlineMinutes").asLong()) : null, null, OrderReason.AGENT_PROPOSAL, legs));
                result.put("basketId", basket.id().toString()).put("state", basket.status().name());
            }
            case POSITION_CLOSE -> {
                HejjeOrder order = execution.closePosition(a.instrumentId(), Product.valueOf(a.proposal().path("product").asText("MIS")), null);
                result.put("orderId", order.id().toString()).put("executedIntentId", order.intentId().toString()).put("state", order.state().name());
            }
        }
        return result;
    }

    private static Price priceOf(JsonNode node, String field) {
        return node.hasNonNull(field) ? Price.of(node.get(field).decimalValue().setScale(2, java.math.RoundingMode.HALF_UP)) : null;
    }

    public Approval reject(UUID id, String reason, String key, HejjePrincipal approver) {
        requireKey(key);
        expireDue();
        Approval a = store.find(id).orElseThrow(() -> new ApprovalException.NotFound(id));
        if (a.status() == ApprovalStatus.REJECTED && key.equals(a.decisionKey())) {
            return a;
        }
        String note = reason == null || reason.isBlank() ? "rejected" : reason.strip();
        if (a.status() != ApprovalStatus.PENDING || !store.reject(id, approver.name(), clock.now(), note, key)) {
            throw new ApprovalException.Conflict("Approval " + id + " is " + store.find(id).map(Approval::status).orElse(a.status()));
        }
        decline(a, "rejected by " + approver.name());
        audit.record(AuditEvent.of(AuditEventType.USER_REJECTED, ActorType.USER).withActorId(approver.name()).withOrderIntentId(a.intentId())
                .withOrderId(a.orderId()).withStrategyId(a.strategyId()).withSignalId(a.signalId())
                .withPayload(Map.of("approvalId", a.id().toString(), "kind", a.kind().name(), "reason", note)));
        Approval done = store.find(id).orElseThrow();
        notify(done);
        return done;
    }

    /** Expires overdue approvals; returns how many. */
    public int expireDue() {
        List<Approval> expired = store.expireDue(clock.now());
        for (Approval a : expired) {
            decline(a, "approval expired");
            audit.record(AuditEvent.of(AuditEventType.APPROVAL_EXPIRED, ActorType.SYSTEM).withActorId("approvals").withOrderIntentId(a.intentId())
                    .withOrderId(a.orderId()).withStrategyId(a.strategyId()).withSignalId(a.signalId())
                    .withPayload(Map.of("approvalId", a.id().toString(), "kind", a.kind().name(), "expiresAt", a.expiresAt().toString())));
            notify(a);
        }
        return expired.size();
    }

    private void finishFailed(Approval a, HejjePrincipal approver, String note, JsonNode result) {
        store.fail(a.id(), approver.name(), clock.now(), note, result);
        decline(a, note);
        audit.record(AuditEvent.of(AuditEventType.APPROVAL_FAILED, ActorType.USER).withActorId(approver.name()).withOrderIntentId(a.intentId())
                .withOrderId(a.orderId()).withStrategyId(a.strategyId()).withSignalId(a.signalId())
                .withPayload(Map.of("approvalId", a.id().toString(), "kind", a.kind().name(), "reason", note)));
        store.find(a.id()).ifPresent(this::notify);
    }

    private void decline(Approval a, String reason) {
        if (a.intentId() != null && a.kind() == ApprovalKind.ORDER_NEW) {
            orders.findIntent(a.intentId()).filter(i -> i.status() == IntentStatus.PROPOSED)
                    .ifPresent(i -> orders.updateIntentStatus(i.withStatus(IntentStatus.DECLINED, List.of(reason))));
        }
    }

    private void notify(Approval a) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", a.id().toString());
        data.put("status", a.status().name());
        data.put("kind", a.kind().name());
        data.put("summary", a.summary());
        events.publishEvent(new ClientNotification("approval", data));
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
    }
}
