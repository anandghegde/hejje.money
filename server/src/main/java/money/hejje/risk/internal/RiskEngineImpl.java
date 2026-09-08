package money.hejje.risk.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerOrderRequest;
import money.hejje.broker.BrokerSessionState;
import money.hejje.broker.OrderMargin;
import money.hejje.common.Money;
import money.hejje.common.Price;
import money.hejje.common.Validity;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.common.time.HejjeClock;
import money.hejje.market.MarketService;
import money.hejje.orders.OrderIntent;
import money.hejje.orders.Position;
import money.hejje.orders.OrderService;
import money.hejje.risk.AccountSnapshot;
import money.hejje.risk.RiskCheck;
import money.hejje.risk.RiskDecision;
import money.hejje.risk.RiskEngine;
import money.hejje.risk.RiskLimits;
import money.hejje.risk.RiskService;
import org.springframework.stereotype.Component;

/** The real risk engine (M1.5): builds the snapshot, resolves inputs, runs the controls, and auto-trips on daily loss. */
@Component
public class RiskEngineImpl implements RiskEngine {

    private final RiskLimitsStore limitsStore;
    private final KillSwitchStore killSwitchStore;
    private final AccountSnapshotBuilder snapshots;
    private final InstrumentService instruments;
    private final MarketService market;
    private final OrderService orders;
    private final BrokerAdapter broker;
    private final money.hejje.system.ExecutionReadiness readiness;
    private final HejjeClock clock;
    private final RiskService riskService;

    RiskEngineImpl(RiskLimitsStore limitsStore, KillSwitchStore killSwitchStore, AccountSnapshotBuilder snapshots,
            InstrumentService instruments, MarketService market, OrderService orders, BrokerAdapter broker,
            money.hejje.system.ExecutionReadiness readiness, HejjeClock clock, RiskService riskService) {
        this.limitsStore = limitsStore;
        this.killSwitchStore = killSwitchStore;
        this.snapshots = snapshots;
        this.instruments = instruments;
        this.market = market;
        this.orders = orders;
        this.broker = broker;
        this.readiness = readiness;
        this.clock = clock;
        this.riskService = riskService;
    }

    @Override
    public RiskDecision evaluate(OrderIntent intent) {
        RiskLimits limits = limitsStore.find(intent.mode());
        AccountSnapshot snapshot = snapshots.build(intent.mode());

        // auto-trip the kill switch when the daily loss limit is already breached
        if (snapshot.totalPnl().negate().compareTo(limits.maxLossPerDay()) >= 0) {
            riskService.autoTrip(intent.mode(), "DAILY_LOSS");
        }

        RiskInputs in = resolve(intent, snapshot, limits);
        List<RiskCheck> checks = new ArrayList<>();
        checks.add(RiskControls.brokerConnected(in));
        checks.add(RiskControls.readiness(in));
        if (!in.exposureReducing()) {
            checks.add(RiskControls.killSwitch(in));
            checks.add(RiskControls.dailyLoss(in));
            checks.add(RiskControls.realizedLoss(in));
            checks.add(RiskControls.totalLoss(in));
            checks.add(RiskControls.openPositions(in));
            checks.add(RiskControls.tradesPerDay(in));
            checks.add(RiskControls.riskPerTrade(in));
            checks.add(RiskControls.quantity(in));
            checks.add(RiskControls.notional(in));
            checks.add(RiskControls.marginUtilization(in));
            checks.add(RiskControls.minRewardRisk(in));
            checks.add(RiskControls.mandatoryStop(in));
            checks.add(RiskControls.maxStopDistance(in));
            checks.add(RiskControls.tradingWindow(in));
            checks.add(RiskControls.averagingDown(in));
            checks.add(RiskControls.reentryCooldown(in, clock.now()));
            checks.add(RiskControls.consecutiveLosses(in));
        }
        boolean approved = checks.stream().allMatch(RiskCheck::passed);
        return approved ? RiskDecision.approved(checks) : RiskDecision.rejected(checks);
    }

    private RiskInputs resolve(OrderIntent intent, AccountSnapshot snapshot, RiskLimits limits) {
        Instrument instrument = instruments.findById(intent.instrumentId()).orElse(null);
        int lotSize = instrument == null ? 1 : instrument.lotSize();
        BigDecimal lastPrice = market.lastPrice(intent.instrumentId()).orElse(null);
        BigDecimal referencePrice = intent.limitPrice() != null ? intent.limitPrice().value() : lastPrice;

        Money estimatedMargin = estimateMargin(intent);
        boolean killSwitchStop = killSwitchStore.find(intent.mode()).stopNewOrders();
        boolean connected = broker.sessionState() == BrokerSessionState.CONNECTED;
        boolean executionEnabled = readiness.isExecutionEnabled();

        int currentNet = snapshot.netPositionQty().getOrDefault(intent.instrumentId(), 0);
        boolean losing = isInstrumentLosing(intent, currentNet, lastPrice);
        int signed = intent.side() == money.hejje.common.Side.BUY ? intent.quantity().value() : -intent.quantity().value();
        boolean exposureReducing = intent.isExposureReducing() || (currentNet != 0 && Integer.signum(currentNet) != Integer.signum(signed));

        return new RiskInputs(intent, snapshot, limits, lotSize, referencePrice, estimatedMargin, killSwitchStop, connected,
                executionEnabled, clock.nowIst().toLocalTime(), currentNet, losing, exposureReducing);
    }

    private boolean isInstrumentLosing(OrderIntent intent, int currentNet, BigDecimal lastPrice) {
        if (currentNet == 0 || lastPrice == null) {
            return false;
        }
        return orders.positions(intent.mode()).stream()
                .filter(p -> p.instrumentId().equals(intent.instrumentId()) && !p.isFlat())
                .findFirst()
                .map(Position::averagePrice)
                .map(avg -> currentNet > 0 ? lastPrice.compareTo(avg) < 0 : lastPrice.compareTo(avg) > 0)
                .orElse(false);
    }

    private Money estimateMargin(OrderIntent intent) {
        try {
            BrokerOrderRequest request = new BrokerOrderRequest(intent.instrumentId(), intent.side(), intent.quantity(),
                    intent.orderType(), intent.product(), intent.limitPrice(), intent.triggerPrice(), Validity.DAY, null);
            List<OrderMargin> margins = broker.getOrderMargins(List.of(request));
            return margins.isEmpty() ? null : margins.get(0).total();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
