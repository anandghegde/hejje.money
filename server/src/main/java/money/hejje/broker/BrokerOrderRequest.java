package money.hejje.broker;

import java.util.UUID;
import money.hejje.common.OrderType;
import money.hejje.common.Price;
import money.hejje.common.Product;
import money.hejje.common.Quantity;
import money.hejje.common.Side;
import money.hejje.common.Validity;

/**
 * A new order in Hejje terms. The adapter maps it to broker fields.
 *
 * @param instrumentId Hejje instrument
 * @param side         BUY or SELL
 * @param quantity     units
 * @param orderType    MARKET, LIMIT, SL or SL_M
 * @param product      MIS, CNC or NRML
 * @param limitPrice   required for LIMIT and SL
 * @param triggerPrice required for SL and SL_M
 * @param validity     DAY or IOC
 * @param tag          short client reference echoed back by the broker (at most 20 alphanumeric characters)
 */
public record BrokerOrderRequest(
        UUID instrumentId,
        Side side,
        Quantity quantity,
        OrderType orderType,
        Product product,
        Price limitPrice,
        Price triggerPrice,
        Validity validity,
        String tag) {

    public static final int MAX_TAG_LENGTH = 20;

    public BrokerOrderRequest {
        if (instrumentId == null || side == null || quantity == null || orderType == null || product == null) {
            throw new IllegalArgumentException("Order request needs instrument, side, quantity, order type and product");
        }
        validity = validity == null ? Validity.DAY : validity;
        if ((orderType == OrderType.LIMIT || orderType == OrderType.SL) && limitPrice == null) {
            throw new IllegalArgumentException(orderType + " orders need a limit price");
        }
        if ((orderType == OrderType.SL || orderType == OrderType.SL_M) && triggerPrice == null) {
            throw new IllegalArgumentException(orderType + " orders need a trigger price");
        }
        if (tag != null && (tag.length() > MAX_TAG_LENGTH || !tag.matches("[A-Za-z0-9]*"))) {
            throw new IllegalArgumentException("Tag must be at most 20 alphanumeric characters: " + tag);
        }
    }
}
