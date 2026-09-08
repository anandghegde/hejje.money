package money.hejje.broker;

import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Quantity;
import money.hejje.common.Validity;

/** Fields of an open order that may change. Null means "leave unchanged". */
public record BrokerModifyRequest(Quantity quantity, OrderType orderType, Price limitPrice, Price triggerPrice, Validity validity) {

    public boolean isEmpty() {
        return quantity == null && orderType == null && limitPrice == null && triggerPrice == null && validity == null;
    }
}
