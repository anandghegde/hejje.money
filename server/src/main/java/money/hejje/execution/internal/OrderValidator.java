package money.hejje.execution.internal;

import java.util.ArrayList;
import java.util.List;
import money.hejje.common.OrderType;
import money.hejje.instruments.Instrument;
import money.hejje.instruments.InstrumentService;
import money.hejje.common.time.HejjeClock;
import money.hejje.execution.LiveTradingGate;
import money.hejje.orders.OrderIntent;
import org.springframework.stereotype.Component;

/** Deterministic pre-broker validation of an intent (PRD section 36 pipeline). Never calls the broker. */
@Component
public class OrderValidator {

    private final InstrumentService instruments;
    private final HejjeClock clock;
    private final LiveTradingGate gate;

    OrderValidator(InstrumentService instruments, HejjeClock clock, LiveTradingGate gate) {
        this.instruments = instruments;
        this.clock = clock;
        this.gate = gate;
    }

    public List<String> validate(OrderIntent intent) {
        List<String> errors = new ArrayList<>();
        Instrument instrument = instruments.findById(intent.instrumentId()).orElse(null);
        if (instrument == null) {
            errors.add("Unknown instrument " + intent.instrumentId());
        } else {
            if (!instrument.active()) {
                errors.add("Instrument " + instrument.hejjeSymbol() + " is not active");
            }
            if (!intent.quantity().isMultipleOf(instrument.lotSize())) {
                errors.add("Quantity " + intent.quantity() + " is not a multiple of lot size " + instrument.lotSize());
            }
            if (intent.limitPrice() != null && !intent.limitPrice().isAlignedTo(instrument.tickSize())) {
                errors.add("Limit price " + intent.limitPrice() + " is not on the tick size " + instrument.tickSize());
            }
            if (intent.triggerPrice() != null && !intent.triggerPrice().isAlignedTo(instrument.tickSize())) {
                errors.add("Trigger price " + intent.triggerPrice() + " is not on the tick size " + instrument.tickSize());
            }
        }
        if ((intent.orderType() == OrderType.LIMIT || intent.orderType() == OrderType.SL) && intent.limitPrice() == null) {
            errors.add(intent.orderType() + " orders need a limit price");
        }
        if ((intent.orderType() == OrderType.SL || intent.orderType() == OrderType.SL_M) && intent.triggerPrice() == null) {
            errors.add(intent.orderType() + " orders need a trigger price");
        }
        if (!intent.isExposureReducing() && !clock.isSessionOpen()) {
            errors.add("Market is closed (no AMO support yet); only closes are allowed outside the session");
        }
        // the live-trading gate composes broker session, kill switch and every readiness check
        errors.addAll(gate.check(intent));
        return errors;
    }
}
