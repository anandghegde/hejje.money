package money.hejje.agent.internal.tools;

import static money.hejje.agent.internal.tools.MarketContextTools.INSTRUMENT_PROP;
import static money.hejje.agent.internal.tools.ToolSupport.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import money.hejje.agent.AgentTool;
import money.hejje.agent.AgentToolProvider;
import money.hejje.agent.Approval;
import money.hejje.agent.ApprovalService;
import money.hejje.agent.Proposal;
import money.hejje.agent.ProposalSpec;
import money.hejje.agent.ToolContext;
import money.hejje.agent.ToolException;
import money.hejje.agent.ToolStatus;
import money.hejje.agent.internal.OrderProposals;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.orders.HejjeOrder;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import money.hejje.orders.Position;
import money.hejje.risk.policy.PolicyDecision;
import org.springframework.stereotype.Component;

/**
 * Order-preparation tools (scope {@code orders:prepare}, PRD 28/30): {@code prepare_order} is a side-effect-free dry run
 * (Hejje sizes, risk and policy decide); the {@code *_intent} tools only create approvals a human decides on. None of
 * them places, modifies, cancels or closes anything by itself.
 */
@Component
public class ProposalTools implements AgentToolProvider {

    public record PrepareInput(String signalId, String instrument, String side, BigDecimal riskRupees, BigDecimal entry, BigDecimal stop, BigDecimal target,
            String product, String strategy) {}

    public record SubmitInput(String signalId, String instrument, String side, BigDecimal riskRupees, BigDecimal entry, BigDecimal stop, BigDecimal target,
            String product, String strategy, String rationale) {}

    public record ModifyInput(String orderId, Integer quantity, String orderType, BigDecimal limitPrice, BigDecimal triggerPrice, String rationale) {}

    public record CancelInput(String orderId, String rationale) {}

    public record CloseInput(String instrument, String product, String rationale) {}

    public record BasketLegInput(String instrument, String side, Integer quantity, String orderType, String product, BigDecimal limitPrice, BigDecimal stopPrice,
            BigDecimal targetPrice, Boolean hedgeFirst) {}

    public record BasketInput(String name, String policy, String rollback, Integer deadlineMinutes, java.util.List<BasketLegInput> legs, String rationale) {}

    public record ApprovalView(UUID approvalId, String kind, String status, String summary, UUID intentId, Instant expiresAt, String policyDecision,
            String policyReason, String message) {}

    static final String SPEC_PROPS = """
            "signalId":{"type":"string","format":"uuid","description":"Prepare the order for this active signal (other fields are then ignored)"},
            "instrument":%s,"side":{"type":"string","enum":["BUY","SELL"]},
            "riskRupees":{"type":"number","minimum":1,"description":"Maximum loss in rupees if the stop is hit; Hejje sizes the quantity from it"},
            "entry":{"type":"number","minimum":0,"description":"Reference entry price (default the last price)"},
            "stop":{"type":"number","minimum":0},"target":{"type":"number","minimum":0},
            "product":{"type":"string","enum":["MIS","CNC","NRML"]},
            "strategy":{"type":"string","description":"Strategy id or slug the order belongs to (its deployment's autonomy level applies)"}"""
            .formatted(INSTRUMENT_PROP);
    static final String RATIONALE = "\"rationale\":{\"type\":\"string\",\"maxLength\":500,\"description\":\"Why, in one or two sentences, shown to the approver\"}";
    static final Set<OrderState> OPEN = Set.of(OrderState.OPEN, OrderState.BROKER_ACCEPTED, OrderState.PARTIALLY_FILLED);

    private final OrderProposals proposals;
    private final ApprovalService approvals;
    private final OrderService orders;
    private final ToolSupport support;

    ProposalTools(OrderProposals proposals, ApprovalService approvals, OrderService orders, ToolSupport support) {
        this.proposals = proposals;
        this.approvals = approvals;
        this.orders = orders;
        this.support = support;
    }

    @Override
    public List<AgentTool> tools() {
        return List.of(
                AgentTool.of("prepare_order", "Dry run of an order: Hejje sizes it from the rupee risk and stop (or the signal), runs the risk checks and the "
                        + "approval policy, and returns the proposal. Nothing is created; use submit_order_intent to ask a human to approve it.",
                        ScopeCatalog.ORDERS_PREPARE, schema("{\"type\":\"object\",\"properties\":{" + SPEC_PROPS + "},\"additionalProperties\":false}"),
                        PrepareInput.class, Proposal.class, this::prepare),
                AgentTool.transactional("submit_order_intent", "Creates an order proposal (PROPOSED intent) and an approval request for a human; the order is "
                        + "placed only if a human approves it in the Approvals inbox before it expires. Same input as prepare_order plus a rationale.",
                        ScopeCatalog.ORDERS_PREPARE, schema("{\"type\":\"object\",\"properties\":{" + SPEC_PROPS + "," + RATIONALE + "},\"additionalProperties\":false}"),
                        SubmitInput.class, ApprovalView.class, this::submit),
                AgentTool.transactional("modify_order_intent", "Asks a human to approve modifying an open order (quantity, order type, limit or trigger price).",
                        ScopeCatalog.ORDERS_PREPARE, schema("""
                                {"type":"object","properties":{"orderId":{"type":"string","format":"uuid"},"quantity":{"type":"integer","minimum":1},
                                 "orderType":{"type":"string","enum":["MARKET","LIMIT","SL","SL_M"]},"limitPrice":{"type":"number","minimum":0},
                                 "triggerPrice":{"type":"number","minimum":0},%s},"required":["orderId"],"additionalProperties":false}""".formatted(RATIONALE)),
                        ModifyInput.class, ApprovalView.class, this::modify),
                AgentTool.transactional("cancel_order_intent", "Asks a human to approve cancelling an open order.", ScopeCatalog.ORDERS_PREPARE, schema("""
                                {"type":"object","properties":{"orderId":{"type":"string","format":"uuid"},%s},"required":["orderId"],"additionalProperties":false}"""
                                .formatted(RATIONALE)),
                        CancelInput.class, ApprovalView.class, this::cancel),
                AgentTool.transactional("close_position_intent", "Asks a human to approve closing the open position in an instrument.", ScopeCatalog.ORDERS_PREPARE,
                        schema("""
                                {"type":"object","properties":{"instrument":%s,"product":{"type":"string","enum":["MIS","CNC","NRML"]},%s},
                                 "required":["instrument"],"additionalProperties":false}""".formatted(INSTRUMENT_PROP, RATIONALE)),
                        CloseInput.class, ApprovalView.class, this::close),
                AgentTool.transactional("submit_basket_intent", "Asks a human to approve a basket: up to 20 orders executed together through the normal "
                        + "pipeline, hedge legs first and one at a time, with ALL_OR_NOTHING (stop at the first failed leg; rollback CLOSE_FILLED_LEGS closes the "
                        + "filled ones) or BEST_EFFORT. Every leg is still validated and risk-checked; nothing is placed unless a human approves it.",
                        ScopeCatalog.ORDERS_PREPARE, schema("""
                                {"type":"object","properties":{"name":{"type":"string","maxLength":80},
                                 "policy":{"type":"string","enum":["ALL_OR_NOTHING","BEST_EFFORT"]},"rollback":{"type":"string","enum":["NONE","CLOSE_FILLED_LEGS"]},
                                 "deadlineMinutes":{"type":"integer","minimum":1,"maximum":375},
                                 "legs":{"type":"array","minItems":1,"maxItems":20,"items":{"type":"object","properties":{"instrument":%s,
                                   "side":{"type":"string","enum":["BUY","SELL"]},"quantity":{"type":"integer","minimum":1},
                                   "orderType":{"type":"string","enum":["MARKET","LIMIT"]},"product":{"type":"string","enum":["MIS","CNC","NRML"]},
                                   "limitPrice":{"type":"number","minimum":0},"stopPrice":{"type":"number","minimum":0},"targetPrice":{"type":"number","minimum":0},
                                   "hedgeFirst":{"type":"boolean"}},"required":["instrument","side","quantity"],"additionalProperties":false}},%s},
                                 "required":["legs"],"additionalProperties":false}""".formatted(INSTRUMENT_PROP, RATIONALE)),
                        BasketInput.class, ApprovalView.class, this::basket));
    }

    Proposal prepare(PrepareInput in, ToolContext ctx) {
        OrderProposals.Prepared p = proposals.prepare(new ProposalSpec(in.signalId(), in.instrument(), in.side(), in.riskRupees(), in.entry(), in.stop(), in.target(),
                in.product(), in.strategy()), ctx.principal());
        if (p.policy().decision() == PolicyDecision.DENY) {
            throw new ToolException(ToolStatus.DENIED, "Policy denies: " + p.policy().reason());
        }
        return p.proposal();
    }

    ApprovalView submit(SubmitInput in, ToolContext ctx) {
        OrderProposals.Prepared p = proposals.prepare(new ProposalSpec(in.signalId(), in.instrument(), in.side(), in.riskRupees(), in.entry(), in.stop(), in.target(),
                in.product(), in.strategy()), ctx.principal());
        return view(approvals.proposeOrder(ctx, p, in.rationale()));
    }

    ApprovalView modify(ModifyInput in, ToolContext ctx) {
        HejjeOrder order = openOrder(in.orderId());
        if (in.quantity() == null && in.orderType() == null && in.limitPrice() == null && in.triggerPrice() == null) {
            throw ToolException.invalid("Give at least one of quantity, orderType, limitPrice, triggerPrice");
        }
        return view(approvals.proposeModify(ctx, order, in.quantity(), in.orderType() == null ? null : OrderType.valueOf(in.orderType()), in.limitPrice(),
                in.triggerPrice(), in.rationale()));
    }

    ApprovalView cancel(CancelInput in, ToolContext ctx) {
        return view(approvals.proposeCancel(ctx, openOrder(in.orderId()), in.rationale()));
    }

    ApprovalView close(CloseInput in, ToolContext ctx) {
        UUID instrumentId = support.instrument(in.instrument()).id();
        Position position = orders.openPositions(proposals.mode()).stream()
                .filter(p -> p.instrumentId().equals(instrumentId) && (in.product() == null || p.product() == Product.valueOf(in.product()))).findFirst()
                .orElseThrow(() -> ToolException.notFound("No open position in " + in.instrument()));
        return view(approvals.proposeClose(ctx, position, in.rationale()));
    }

    ApprovalView basket(BasketInput in, ToolContext ctx) {
        java.util.List<java.util.Map<String, Object>> legs = new java.util.ArrayList<>();
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (BasketLegInput l : in.legs()) {
            money.hejje.instruments.Instrument instrument = support.instrument(l.instrument());
            String orderType = l.orderType() == null ? "MARKET" : l.orderType();
            if ("LIMIT".equals(orderType) && l.limitPrice() == null) {
                throw ToolException.invalid("A LIMIT leg needs a limitPrice (" + l.instrument() + ")");
            }
            java.util.Map<String, Object> leg = new java.util.LinkedHashMap<>();
            leg.put("instrumentId", instrument.id().toString());
            leg.put("instrument", instrument.hejjeSymbol().format());
            leg.put("side", l.side());
            leg.put("quantity", l.quantity());
            leg.put("orderType", orderType);
            leg.put("product", l.product() == null ? "MIS" : l.product());
            if (l.limitPrice() != null) {
                leg.put("limitPrice", l.limitPrice());
            }
            if (l.stopPrice() != null) {
                leg.put("stopPrice", l.stopPrice());
            }
            if (l.targetPrice() != null) {
                leg.put("targetPrice", l.targetPrice());
            }
            leg.put("hedgeFirst", Boolean.TRUE.equals(l.hedgeFirst()));
            legs.add(leg);
            parts.add(l.side() + " " + l.quantity() + " " + instrument.hejjeSymbol().format() + (Boolean.TRUE.equals(l.hedgeFirst()) ? " (hedge first)" : ""));
        }
        java.util.Map<String, Object> proposal = new java.util.LinkedHashMap<>();
        proposal.put("name", in.name());
        proposal.put("policy", in.policy() == null ? "ALL_OR_NOTHING" : in.policy());
        proposal.put("rollback", in.rollback() == null ? "NONE" : in.rollback());
        if (in.deadlineMinutes() != null) {
            proposal.put("deadlineMinutes", in.deadlineMinutes());
        }
        proposal.put("legs", legs);
        String summary = "Basket" + (in.name() == null ? "" : " " + in.name()) + " (" + proposal.get("policy") + "): " + String.join(", ", parts);
        return view(approvals.proposeBasket(ctx, proposal, summary, in.rationale()));
    }

    private HejjeOrder openOrder(String id) {
        HejjeOrder order = orders.findById(UUID.fromString(id)).orElseThrow(() -> ToolException.notFound("Unknown order " + id));
        if (!OPEN.contains(order.state())) {
            throw ToolException.conflict("Order " + id + " is " + order.state());
        }
        if (order.mode() != proposals.mode()) {
            throw ToolException.conflict("Order belongs to mode " + order.mode());
        }
        return order;
    }

    private static ApprovalView view(Approval a) {
        JsonNode policy = a.policy() == null ? null : a.policy();
        return new ApprovalView(a.id(), a.kind().name(), a.status().name(), a.summary(), a.intentId(), a.expiresAt(),
                policy == null ? null : policy.path("decision").asText(null), policy == null ? null : policy.path("reason").asText(null),
                "Proposal created — a human must approve it in the Approvals inbox (web /approvals, TUI `hejje approve " + a.id() + "`) before "
                        + a.expiresAt() + "; nothing has been placed yet.");
    }
}
