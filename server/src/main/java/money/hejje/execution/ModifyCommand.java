package money.hejje.execution;

import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Quantity;

/** Fields to modify on an open order; null leaves a field unchanged. */
public record ModifyCommand(Quantity quantity, OrderType orderType, Price limitPrice, Price triggerPrice) {
}
