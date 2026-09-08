package money.hejje.risk.internal;

import java.math.BigDecimal;
import java.time.LocalTime;
import money.hejje.common.Money;
import money.hejje.orders.OrderIntent;
import money.hejje.risk.AccountSnapshot;
import money.hejje.risk.RiskLimits;

/** Everything a risk control needs, resolved once by the engine. */
public record RiskInputs(
        OrderIntent intent,
        AccountSnapshot snapshot,
        RiskLimits limits,
        int lotSize,
        BigDecimal referencePrice,   // limit price, else last price, else null
        Money estimatedMargin,        // from getOrderMargins, else null
        boolean killSwitchStop,
        boolean brokerConnected,
        boolean executionEnabled,
        LocalTime nowIst,
        int currentNet,
        boolean instrumentLosing,
        boolean exposureReducing) {
}
