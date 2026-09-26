package money.hejje.broker.zerodha;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.InputException;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.NetworkException;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.OrderException;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.PermissionException;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.TokenException;
import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.kiteconnect.utils.MultipleDateFormatDeserializer;
import com.zerodhatech.models.Depth;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.Position;
import com.zerodhatech.models.Tick;
import com.zerodhatech.models.Trade;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerInstrumentRef;
import money.hejje.broker.BrokerModifyRequest;
import money.hejje.broker.BrokerOrder;
import money.hejje.broker.BrokerOrderRequest;
import money.hejje.broker.BrokerOrderStatus;
import money.hejje.broker.BrokerPosition;
import money.hejje.broker.BrokerTrade;
import money.hejje.common.Money;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.common.Timeframe;
import money.hejje.common.Validity;
import money.hejje.common.event.MarketTick;

/** Pure mapping between Kite Connect models/strings and Hejje broker models. */
public final class KiteMapper {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter CANDLE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Date.class, new MultipleDateFormatDeserializer("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"))
            .create();

    private KiteMapper() {
    }

    // --- enums ------------------------------------------------------------------------------------------------------

    static String orderType(OrderType type) {
        return switch (type) {
            case MARKET -> Constants.ORDER_TYPE_MARKET;
            case LIMIT -> Constants.ORDER_TYPE_LIMIT;
            case SL -> Constants.ORDER_TYPE_SL;
            case SL_M -> Constants.ORDER_TYPE_SLM;
        };
    }

    static OrderType orderType(String kite) {
        if (kite == null) {
            return null;
        }
        return switch (kite) {
            case Constants.ORDER_TYPE_MARKET -> OrderType.MARKET;
            case Constants.ORDER_TYPE_LIMIT -> OrderType.LIMIT;
            case Constants.ORDER_TYPE_SL -> OrderType.SL;
            case Constants.ORDER_TYPE_SLM -> OrderType.SL_M;
            default -> null;
        };
    }

    static Product product(String kite) {
        try {
            return kite == null ? null : Product.valueOf(kite);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Side side(String kite) {
        return kite == null ? null : Constants.TRANSACTION_TYPE_SELL.equals(kite) ? Side.SELL : Side.BUY;
    }

    static Validity validity(String kite) {
        return Constants.VALIDITY_IOC.equals(kite) ? Validity.IOC : Validity.DAY;
    }

    /** Kite order status strings to the normalized enum. */
    public static BrokerOrderStatus status(String kite) {
        if (kite == null) {
            return BrokerOrderStatus.UNKNOWN;
        }
        String s = kite.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "COMPLETE" -> BrokerOrderStatus.COMPLETE;
            case "REJECTED" -> BrokerOrderStatus.REJECTED;
            case "CANCELLED", "LAPSED" -> BrokerOrderStatus.CANCELLED;
            case "OPEN" -> BrokerOrderStatus.OPEN;
            case "TRIGGER PENDING" -> BrokerOrderStatus.TRIGGER_PENDING;
            case "MODIFY PENDING", "MODIFY VALIDATION PENDING", "MODIFIED" -> BrokerOrderStatus.MODIFY_PENDING;
            case "CANCEL PENDING" -> BrokerOrderStatus.CANCEL_PENDING;
            case "PUT ORDER REQ RECEIVED", "VALIDATION PENDING", "OPEN PENDING", "AMO REQ RECEIVED", "UPDATE" -> BrokerOrderStatus.PENDING;
            default -> BrokerOrderStatus.UNKNOWN;
        };
    }

    static String interval(Timeframe timeframe) {
        return switch (timeframe) {
            case M1 -> "minute";
            case M3 -> "3minute";
            case M5 -> "5minute";
            case M15 -> "15minute";
            case H1 -> "60minute";
            case D1 -> "day";
        };
    }

    static String tickMode(MarketTick.Mode mode) {
        return switch (mode) {
            case LTP -> "ltp";
            case QUOTE -> "quote";
            case FULL -> "full";
        };
    }

    // --- errors -------------------------------------------------------------------------------------------------------

    /** Maps a Kite failure to a {@link BrokerException}. {@code KiteException} extends Throwable, hence the broad type. */
    public static BrokerException toBrokerException(Throwable t) {
        if (t instanceof BrokerException b) {
            return b;
        }
        if (t instanceof KiteException k) {
            String message = k.message != null ? k.message : k.getClass().getSimpleName();
            if (k instanceof TokenException) {
                return new BrokerException(BrokerException.Kind.AUTH, message, false, k);
            }
            if (k instanceof NetworkException) {
                return k.code == 429
                        ? new BrokerException(BrokerException.Kind.RATE_LIMIT, message, true, k)
                        : new BrokerException(BrokerException.Kind.NETWORK, message, true, k);
            }
            if (k.code == 429) {
                return new BrokerException(BrokerException.Kind.RATE_LIMIT, message, true, k);
            }
            if (k instanceof InputException) {
                return new BrokerException(BrokerException.Kind.INPUT, message, false, k);
            }
            if (k instanceof OrderException) {
                return new BrokerException(BrokerException.Kind.REJECTED, message, false, k);
            }
            if (k instanceof PermissionException) {
                return new BrokerException(BrokerException.Kind.AUTH, message, false, k);
            }
            if (k.code >= 500) {
                return new BrokerException(BrokerException.Kind.NETWORK, message, true, k);
            }
            return new BrokerException(BrokerException.Kind.UNKNOWN, message, false, k);
        }
        if (t instanceof SocketTimeoutException || t instanceof InterruptedIOException) {
            return new BrokerException(BrokerException.Kind.TIMEOUT, "timeout: " + t.getMessage(), true, t);
        }
        if (t instanceof IOException) {
            return new BrokerException(BrokerException.Kind.NETWORK, "network: " + t.getMessage(), true, t);
        }
        return new BrokerException(BrokerException.Kind.UNKNOWN, String.valueOf(t.getMessage()), false, t);
    }

    // --- requests -----------------------------------------------------------------------------------------------------

    static OrderParams orderParams(BrokerOrderRequest request, BrokerInstrumentRef ref) {
        OrderParams p = new OrderParams();
        p.exchange = ref.exchangeSegment();
        p.tradingsymbol = ref.tradingSymbol();
        p.transactionType = request.side() == Side.BUY ? Constants.TRANSACTION_TYPE_BUY : Constants.TRANSACTION_TYPE_SELL;
        p.quantity = request.quantity().value();
        p.product = request.product().name();
        p.orderType = orderType(request.orderType());
        p.validity = request.validity() == Validity.IOC ? Constants.VALIDITY_IOC : Constants.VALIDITY_DAY;
        if (request.limitPrice() != null && (request.orderType() == OrderType.LIMIT || request.orderType() == OrderType.SL)) {
            p.price = request.limitPrice().value().doubleValue();
        }
        if (request.triggerPrice() != null && (request.orderType() == OrderType.SL || request.orderType() == OrderType.SL_M)) {
            p.triggerPrice = request.triggerPrice().value().doubleValue();
        }
        p.tag = request.tag();
        return p;
    }

    static OrderParams modifyParams(BrokerModifyRequest request) {
        OrderParams p = new OrderParams();
        if (request.quantity() != null) {
            p.quantity = request.quantity().value();
        }
        if (request.orderType() != null) {
            p.orderType = orderType(request.orderType());
        }
        if (request.limitPrice() != null) {
            p.price = request.limitPrice().value().doubleValue();
        }
        if (request.triggerPrice() != null) {
            p.triggerPrice = request.triggerPrice().value().doubleValue();
        }
        if (request.validity() != null) {
            p.validity = request.validity() == Validity.IOC ? Constants.VALIDITY_IOC : Constants.VALIDITY_DAY;
        }
        return p;
    }

    // --- responses ----------------------------------------------------------------------------------------------------

    /** Parses a Kite order JSON object (postback or ticker payload) with the library's own date handling. */
    public static Order orderFromJson(String json) {
        return GSON.fromJson(json, Order.class);
    }

    static BrokerOrder order(Order o, Function<Order, UUID> instrumentId) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("exchange_order_id", o.exchangeOrderId);
        raw.put("variety", o.orderVariety);
        raw.put("status", o.status);
        raw.put("status_message", o.statusMessage);
        raw.put("placed_by", o.accountId);
        raw.put("guid", o.guid);
        return new BrokerOrder(
                o.orderId,
                instrumentId.apply(o),
                o.tradingSymbol,
                o.exchange,
                side(o.transactionType),
                intOf(o.quantity),
                intOf(o.filledQuantity),
                intOf(o.pendingQuantity),
                decimal(o.averagePrice),
                orderType(o.orderType),
                product(o.product),
                decimal(o.price),
                decimal(o.triggerPrice),
                validity(o.validity),
                status(o.status),
                o.status,
                o.statusMessage,
                o.tag,
                o.parentOrderId,
                instant(o.orderTimestamp),
                o.exchangeUpdateTimestamp != null ? instant(o.exchangeUpdateTimestamp) : instant(o.exchangeTimestamp),
                raw);
    }

    static BrokerTrade trade(Trade t, UUID instrumentId) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("exchange_order_id", t.exchangeOrderId);
        raw.put("instrument_token", t.instrumentToken);
        return new BrokerTrade(t.tradeId, t.orderId, instrumentId, t.tradingSymbol, t.exchange, side(t.transactionType),
                product(t.product), intOf(t.quantity), decimal(t.averagePrice),
                t.fillTimestamp != null ? instant(t.fillTimestamp) : instant(t.exchangeTimestamp), raw);
    }

    static BrokerPosition position(Position p, UUID instrumentId) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("instrument_token", p.instrumentToken);
        raw.put("multiplier", p.multiplier);
        raw.put("overnight_quantity", p.overnightQuantity);
        return new BrokerPosition(instrumentId, p.tradingSymbol, p.exchange, product(p.product), p.netQuantity,
                decimal(p.averagePrice), (int) p.dayBuyQuantity, (int) p.daySellQuantity, decimal(p.dayBuyValue),
                decimal(p.daySellValue), money(p.realised), money(p.unrealised), decimal(p.lastPrice), raw);
    }

    static MarketTick tick(Tick t, UUID instrumentId, Instant fallbackTs) {
        BigDecimal bid = null;
        BigDecimal ask = null;
        Map<String, java.util.ArrayList<Depth>> depth = t.getMarketDepth();
        if (depth != null) {
            List<Depth> buy = depth.get("buy");
            List<Depth> sell = depth.get("sell");
            if (buy != null && !buy.isEmpty() && buy.get(0).getPrice() > 0) {
                bid = decimal(buy.get(0).getPrice());
            }
            if (sell != null && !sell.isEmpty() && sell.get(0).getPrice() > 0) {
                ask = decimal(sell.get(0).getPrice());
            }
        }
        MarketTick.Mode mode = "full".equals(t.getMode()) ? MarketTick.Mode.FULL
                : "quote".equals(t.getMode()) ? MarketTick.Mode.QUOTE : MarketTick.Mode.LTP;
        Instant ts = t.getTickTimestamp() != null ? t.getTickTimestamp().toInstant()
                : t.getLastTradedTime() != null ? t.getLastTradedTime().toInstant() : fallbackTs;
        Long bidQty5 = null;
        Long askQty5 = null;
        Long totalBuy = null;
        Long totalSell = null;
        if (mode == MarketTick.Mode.FULL) { // plan M9.4: depth and day totals only come with FULL mode
            if (depth != null) {
                bidQty5 = quantity5(depth.get("buy"));
                askQty5 = quantity5(depth.get("sell"));
            }
            totalBuy = (long) t.getTotalBuyQuantity();
            totalSell = (long) t.getTotalSellQuantity();
        }
        return new MarketTick(instrumentId, ts, decimal(t.getLastTradedPrice()), bid, ask, t.getVolumeTradedToday(),
                (long) t.getOi(), mode, bidQty5, askQty5, totalBuy, totalSell);
    }

    /** Sum of the quantities of up to five depth levels; null without levels. */
    static Long quantity5(List<Depth> levels) {
        if (levels == null || levels.isEmpty()) {
            return null;
        }
        long sum = 0;
        for (int i = 0; i < Math.min(5, levels.size()); i++) {
            sum += Math.max(0, levels.get(i).getQuantity());
        }
        return sum;
    }

    // --- primitives -----------------------------------------------------------------------------------------------------

    static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal decimal(Double value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : decimal(value.doubleValue());
    }

    static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO.setScale(2);
        }
        try {
            return new BigDecimal(value.trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO.setScale(2);
        }
    }

    static Money money(Double value) {
        return value == null ? Money.ZERO : Money.of(decimal(value));
    }

    static Money money(String value) {
        return Money.of(decimal(value));
    }

    static int intOf(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return new BigDecimal(value.trim()).intValue();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Kite timestamps are IST wall-clock strings; the library parses them in the JVM default zone. This converts the
     * parsed {@link Date} back to the instant the exchange meant.
     */
    static Instant instant(Date kiteDate) {
        if (kiteDate == null) {
            return null;
        }
        return LocalDateTime.ofInstant(kiteDate.toInstant(), ZoneId.systemDefault()).atZone(IST).toInstant();
    }

    /** Inverse of {@link #instant(Date)}: a Date that the library will format as the IST wall-clock time of {@code instant}. */
    static Date kiteDate(Instant instant) {
        return Date.from(LocalDateTime.ofInstant(instant, IST).atZone(ZoneId.systemDefault()).toInstant());
    }

    static LocalDate localDate(Date kiteDate) {
        return kiteDate == null ? null : LocalDateTime.ofInstant(kiteDate.toInstant(), ZoneId.systemDefault()).toLocalDate();
    }

    /** Historical candle timestamps look like {@code 2026-09-08T09:15:00+0530}. */
    static Instant candleInstant(String ts) {
        return OffsetDateTime.parse(ts, CANDLE_TS).toInstant();
    }

    // --- GTT (plan M11.2, docs/swing.md) ------------------------------------------------------------------------------

    private static final DateTimeFormatter GTT_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * A Hejje GTT as Kite's GTT parameters. A MARKET leg is sent with automatic market price protection (-1); Kite
     * accepts MARKET and LIMIT legs.
     */
    static com.zerodhatech.models.GTTParams gttParams(money.hejje.broker.Gtt.Request request, BrokerInstrumentRef ref) {
        com.zerodhatech.models.GTTParams p = new com.zerodhatech.models.GTTParams();
        p.tradingsymbol = ref.tradingSymbol();
        p.exchange = ref.exchangeSegment();
        p.instrumentToken = Integer.parseInt(ref.brokerToken());
        p.triggerType = request.type() == money.hejje.broker.Gtt.Type.OCO ? Constants.OCO : Constants.SINGLE;
        p.lastPrice = request.lastPrice().doubleValue();
        p.triggerPrices = request.triggers().stream().map(BigDecimal::doubleValue).toList();
        p.orders = new java.util.ArrayList<>();
        for (money.hejje.broker.Gtt.Leg leg : request.legs()) {
            com.zerodhatech.models.GTTParams.GTTOrderParams o = p.new GTTOrderParams();
            o.quantity = leg.quantity();
            o.price = leg.price() == null ? 0 : leg.price().doubleValue();
            o.orderType = orderType(leg.orderType());
            o.product = leg.product().name();
            o.transactionType = leg.side() == Side.SELL ? Constants.TRANSACTION_TYPE_SELL : Constants.TRANSACTION_TYPE_BUY;
            o.marketProtection = leg.orderType() == OrderType.MARKET ? -1 : 0;
            p.orders.add(o);
        }
        return p;
    }

    static money.hejje.broker.Gtt.Snapshot gtt(com.zerodhatech.models.GTT g, UUID instrumentId) {
        List<BigDecimal> triggers = g.condition == null || g.condition.triggerValues == null ? List.of()
                : g.condition.triggerValues.stream().map(KiteMapper::decimal).toList();
        List<money.hejje.broker.Gtt.Leg> legs = new java.util.ArrayList<>();
        String triggeredOrderId = null;
        if (g.orders != null) {
            for (com.zerodhatech.models.GTT.GTTOrder o : g.orders) {
                OrderType type = orderType(o.orderType);
                legs.add(new money.hejje.broker.Gtt.Leg(side(o.transactionType), o.quantity, type == null ? OrderType.LIMIT : type, decimal(o.price),
                        product(o.product)));
                if (o.result != null && o.result.orderResult != null && o.result.orderResult.orderId != null && !"-".equals(o.result.orderResult.orderId)) {
                    triggeredOrderId = o.result.orderResult.orderId;
                }
            }
        }
        money.hejje.broker.Gtt.Status status;
        try {
            status = money.hejje.broker.Gtt.Status.valueOf(String.valueOf(g.status).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            status = money.hejje.broker.Gtt.Status.DISABLED; // an unknown state is not an active one
        }
        Map<String, Object> raw = new java.util.LinkedHashMap<>();
        raw.put("status", String.valueOf(g.status));
        raw.put("expires_at", String.valueOf(g.expiresAt));
        if (g.meta != null && g.meta.rejectionReason != null) {
            raw.put("rejection_reason", g.meta.rejectionReason);
        }
        return new money.hejje.broker.Gtt.Snapshot(String.valueOf(g.id), instrumentId, g.condition == null ? null : g.condition.tradingSymbol,
                Constants.OCO.equals(g.triggerType) ? money.hejje.broker.Gtt.Type.OCO : money.hejje.broker.Gtt.Type.SINGLE, status, triggers, legs,
                triggeredOrderId, gttInstant(g.createdAt), gttInstant(g.updatedAt), raw);
    }

    /** GTT timestamps look like {@code 2026-09-08 15:29:56} (IST). */
    static Instant gttInstant(String ts) {
        if (ts == null || ts.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(ts, GTT_TS).atZone(IST).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
