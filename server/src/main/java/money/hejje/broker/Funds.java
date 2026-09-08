package money.hejje.broker;

import java.util.Map;
import money.hejje.common.Money;

/**
 * Account funds (equity segment).
 *
 * @param availableCash cash available for new positions
 * @param usedMargin    margin blocked by open positions and orders
 * @param net           net available balance as the broker reports it
 * @param openingBalance opening balance of the day when known, else null
 */
public record Funds(Money availableCash, Money usedMargin, Money net, Money openingBalance, Map<String, Object> raw) {

    public Funds {
        raw = raw == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(raw));
    }
}
