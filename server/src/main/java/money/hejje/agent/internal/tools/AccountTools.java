package money.hejje.agent.internal.tools;

import static money.hejje.agent.internal.tools.MarketContextTools.INSTRUMENT_PROP;
import static money.hejje.agent.internal.tools.MarketContextTools.NO_INPUT;
import static money.hejje.agent.internal.tools.ToolSupport.name;
import static money.hejje.agent.internal.tools.ToolSupport.rupees;
import static money.hejje.agent.internal.tools.ToolSupport.schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import money.hejje.agent.AgentTool;
import money.hejje.agent.AgentToolProvider;
import money.hejje.agent.ToolContext;
import money.hejje.agent.ToolException;
import money.hejje.agent.internal.tools.MarketContextTools.NoInput;
import money.hejje.audit.AuditEventType;
import money.hejje.audit.AuditPage;
import money.hejje.audit.AuditQuery;
import money.hejje.audit.AuditService;
import money.hejje.common.ExecutionMode;
import money.hejje.common.Money;
import money.hejje.common.Price;
import money.hejje.common.security.ScopeCatalog;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.MarketService;
import money.hejje.orders.OrderService;
import money.hejje.orders.OrderState;
import money.hejje.orders.Position;
import money.hejje.risk.KillSwitchState;
import money.hejje.risk.RiskDashboard;
import money.hejje.risk.RiskService;
import money.hejje.signals.SignalService;
import org.springframework.stereotype.Component;

/**
 * Account tools in the server's execution mode: positions, orders and trades ({@code market:read}, as the REST reads),
 * account risk and position sizing ({@code risk:read}), audit trail ({@code admin}). Sizing is the deterministic
 * {@code PositionSizer}; the agent never computes a quantity itself (PRD 66E).
 */
@Component
public class AccountTools implements AgentToolProvider {

    public record PositionView(UUID positionId, UUID instrumentId, String instrument, String product, UUID strategyId, int netQuantity, BigDecimal averagePrice,
            BigDecimal lastPrice, BigDecimal unrealizedPnl, BigDecimal realizedPnl, BigDecimal fees, Instant openedAt) {}

    public record Positions(String mode, List<PositionView> positions) {}

    public record OrdersInput(String state, Integer limit) {}

    public record OrderView(UUID orderId, UUID intentId, UUID instrumentId, String instrument, String side, int quantity, int filledQuantity,
            BigDecimal averagePrice, String orderType, String product, BigDecimal limitPrice, BigDecimal triggerPrice, String state, String role, Instant placedAt,
            Instant updatedAt) {}

    public record Orders(String mode, List<OrderView> orders) {}

    public record TradesInput(String from, String to) {}

    public record TradeView(UUID tradeId, UUID orderId, UUID instrumentId, String instrument, String side, int quantity, BigDecimal price, Instant ts,
            UUID strategyId) {}

    public record Trades(String mode, LocalDate from, LocalDate to, List<TradeView> trades) {}

    public record AccountRisk(String mode, BigDecimal realizedPnl, BigDecimal unrealizedPnl, BigDecimal netPnl, BigDecimal dailyLossLimit,
            BigDecimal grossExposure, BigDecimal maxGrossExposure, int openPositions, int maxOpenPositions, int tradesToday, int maxTradesPerDay,
            int consecutiveLosses, BigDecimal marginUsedPct, boolean stopNewOrders, String killSwitchReason) {}

    public record SizeInput(BigDecimal entry, BigDecimal stop, BigDecimal riskRupees, String instrument, Integer lotSize, Integer maxQuantity) {}

    public record Sizing(int quantity, BigDecimal riskPerUnit, BigDecimal totalRisk, int lotSize, List<String> notes) {}

    public record AuditInput(String orderId, String type, String from, Integer limit) {}

    public record AuditRow(UUID auditId, Instant ts, String type, String actorType, String actorId, UUID strategyId, UUID signalId, UUID orderIntentId,
            UUID orderId, String brokerRef, String clientSource, Map<String, Object> payload) {}

    public record AuditTrail(long total, List<AuditRow> events) {}

    private final OrderService orders;
    private final SignalService signals;
    private final RiskService risk;
    private final MarketService market;
    private final AuditService audit;
    private final ToolSupport support;
    private final HejjeClock clock;

    AccountTools(OrderService orders, SignalService signals, RiskService risk, MarketService market, AuditService audit, ToolSupport support, HejjeClock clock) {
        this.orders = orders;
        this.signals = signals;
        this.risk = risk;
        this.market = market;
        this.audit = audit;
        this.support = support;
        this.clock = clock;
    }

    @Override
    public List<AgentTool> tools() {
        return List.of(
                AgentTool.of("get_positions", "Open positions in the current execution mode with average price, last price and unrealized/realized P&L (rupees).",
                        ScopeCatalog.MARKET_READ, NO_INPUT, NoInput.class, Positions.class, this::positions),
                AgentTool.of("get_orders", "Today's orders in the current execution mode, optionally filtered by state (newest first).", ScopeCatalog.MARKET_READ,
                        schema("""
                                {"type":"object","properties":{"state":{"type":"string","enum":%s},"limit":{"type":"integer","minimum":1,"maximum":100}},
                                 "additionalProperties":false}""".formatted(ToolSupport.enumJson(OrderState.class))),
                        OrdersInput.class, Orders.class, this::orders),
                AgentTool.of("get_trades", "Fills between two dates (default today, at most 31 days) in the current execution mode.", ScopeCatalog.MARKET_READ,
                        schema("""
                                {"type":"object","properties":{"from":{"type":"string","format":"date"},"to":{"type":"string","format":"date"}},
                                 "additionalProperties":false}"""),
                        TradesInput.class, Trades.class, this::trades),
                AgentTool.of("get_account_risk", "Account risk dashboard (PRD 54): P&L vs the daily loss limit, exposure, open positions, trades today, "
                        + "consecutive losses, margin use and the kill switch.", ScopeCatalog.RISK_READ, NO_INPUT, NoInput.class, AccountRisk.class, this::accountRisk),
                AgentTool.of("calculate_position_size", "Deterministic risk-based quantity: floor(risk / |entry - stop|) rounded down to whole lots, optionally "
                        + "capped. Give the instrument (for its lot size) or lotSize.", ScopeCatalog.RISK_READ, schema("""
                                {"type":"object","properties":{"entry":{"type":"number","minimum":0},"stop":{"type":"number","minimum":0},
                                 "riskRupees":{"type":"number","minimum":1},"instrument":%s,"lotSize":{"type":"integer","minimum":1},
                                 "maxQuantity":{"type":"integer","minimum":1}},"required":["entry","stop","riskRupees"],"additionalProperties":false}"""
                                .formatted(INSTRUMENT_PROP)),
                        SizeInput.class, Sizing.class, this::size),
                AgentTool.of("get_audit_trail", "Audit events, newest first, filtered by order id, event type or start date (at most 50).", ScopeCatalog.ADMIN,
                        schema("""
                                {"type":"object","properties":{"orderId":{"type":"string","format":"uuid"},"type":{"type":"string","enum":%s},
                                 "from":{"type":"string","format":"date"},"limit":{"type":"integer","minimum":1,"maximum":50}},"additionalProperties":false}"""
                                .formatted(ToolSupport.enumJson(AuditEventType.class))),
                        AuditInput.class, AuditTrail.class, this::auditTrail));
    }

    private ExecutionMode mode() {
        return signals.mode();
    }

    Positions positions(NoInput in, ToolContext ctx) {
        Function<UUID, String> symbols = support.symbols();
        List<PositionView> list = new ArrayList<>();
        for (Position p : orders.openPositions(mode())) {
            BigDecimal last = market.lastPrice(p.instrumentId()).orElse(null);
            BigDecimal unrealized = last == null || p.averagePrice() == null ? null
                    : last.subtract(p.averagePrice()).multiply(BigDecimal.valueOf(p.netQuantity())).setScale(2, java.math.RoundingMode.HALF_UP);
            list.add(new PositionView(p.id(), p.instrumentId(), symbols.apply(p.instrumentId()), name(p.product()), p.strategyId(), p.netQuantity(), p.averagePrice(),
                    last, unrealized, rupees(p.realizedPnl()), rupees(p.fees()), p.openedAt()));
        }
        return new Positions(mode().name(), list);
    }

    Orders orders(OrdersInput in, ToolContext ctx) {
        Function<UUID, String> symbols = support.symbols();
        int limit = in.limit() == null ? 50 : in.limit();
        Instant dayStart = clock.today().atStartOfDay(clock.zone()).toInstant();
        List<OrderView> list = orders.query(mode(), in.state() == null ? null : OrderState.valueOf(in.state()), dayStart, clock.now().plusSeconds(1)).stream()
                .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt())).limit(limit)
                .map(o -> new OrderView(o.id(), o.intentId(), o.instrumentId(), symbols.apply(o.instrumentId()), name(o.side()), o.quantity(), o.filledQuantity(),
                        o.averagePrice(), name(o.orderType()), name(o.product()), o.limitPrice(), o.triggerPrice(), name(o.state()), name(o.role()), o.placedAt(),
                        o.updatedAt()))
                .toList();
        return new Orders(mode().name(), list);
    }

    Trades trades(TradesInput in, ToolContext ctx) {
        LocalDate from = ToolSupport.date(in.from(), clock.today());
        LocalDate to = ToolSupport.date(in.to(), clock.today());
        if (to.isBefore(from) || from.plusDays(31).isBefore(to)) {
            throw ToolException.invalid("to must be on or after from and at most 31 days later");
        }
        Function<UUID, String> symbols = support.symbols();
        List<TradeView> list = orders.trades(mode(), from.atStartOfDay(clock.zone()).toInstant(), to.plusDays(1).atStartOfDay(clock.zone()).toInstant()).stream()
                .map(t -> new TradeView(t.id(), t.orderId(), t.instrumentId(), symbols.apply(t.instrumentId()), name(t.side()), t.quantity(), t.price(), t.ts(),
                        t.strategyId()))
                .toList();
        return new Trades(mode().name(), from, to, list);
    }

    AccountRisk accountRisk(NoInput in, ToolContext ctx) {
        RiskDashboard d = risk.dashboard(mode());
        KillSwitchState k = risk.killSwitch(mode());
        return new AccountRisk(d.mode().name(), rupees(d.realizedPnl()), rupees(d.unrealizedPnl()), rupees(d.netPnl()), rupees(d.dailyLossLimit()),
                rupees(d.grossExposure()), rupees(d.maxGrossExposure()), d.openPositions(), d.maxOpenPositions(), d.tradesToday(), d.maxTradesPerDay(),
                d.consecutiveLosses(), d.marginUsedPct(), d.killSwitchStopNewOrders(), k != null && k.stopNewOrders() ? k.reason() : null);
    }

    Sizing size(SizeInput in, ToolContext ctx) {
        int lot = in.lotSize() != null ? in.lotSize() : in.instrument() != null ? support.instrument(in.instrument()).lotSize() : 1;
        int qty = risk.positionSize(Price.of(in.entry()), Price.of(in.stop()), Money.of(in.riskRupees()), lot, in.maxQuantity() == null ? 0 : in.maxQuantity());
        BigDecimal perUnit = in.entry().subtract(in.stop()).abs();
        List<String> notes = new ArrayList<>();
        if (qty == 0) {
            notes.add("Risk of " + in.riskRupees().toPlainString() + " rupees is less than one lot's risk (" + perUnit.multiply(BigDecimal.valueOf(lot)).toPlainString() + ")");
        }
        return new Sizing(qty, perUnit, perUnit.multiply(BigDecimal.valueOf(qty)), lot, notes);
    }

    AuditTrail auditTrail(AuditInput in, ToolContext ctx) {
        int limit = in.limit() == null ? 20 : in.limit();
        Instant from = in.from() == null ? null : LocalDate.parse(in.from()).atStartOfDay(clock.zone()).toInstant();
        AuditPage page = audit.query(new AuditQuery(from, null, in.type() == null ? null : AuditEventType.valueOf(in.type()), ToolSupport.uuid(in.orderId()), 0,
                limit));
        return new AuditTrail(page.total(), page.content().stream().map(r -> new AuditRow(r.id(), r.ts(), name(r.type()), name(r.actorType()), r.actorId(),
                r.strategyId(), r.signalId(), r.orderIntentId(), r.orderId(), r.brokerRef(), r.clientSource(), r.payload())).toList());
    }
}
