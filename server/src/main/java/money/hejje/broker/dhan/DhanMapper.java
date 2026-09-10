package money.hejje.broker.dhan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Map;
import java.util.Set;
import money.hejje.broker.BrokerException;
import money.hejje.broker.BrokerOrderStatus;
import money.hejje.common.Money;
import money.hejje.common.OrderType;
import money.hejje.common.Product;
import money.hejje.common.Side;
import money.hejje.common.Validity;

/** DhanHQ v2 field mapping (docs/broker-dhan.md): products, order types, statuses, times, errors. Pure. */
final class DhanMapper {

    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd().toFormatter();
    private static final DateTimeFormatter QUOTE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    /** Codes after which the access token is no longer usable: the session is dropped. */
    private static final Set<String> SESSION_ERRORS = Set.of("DH-901", "807", "808", "809", "810");

    private DhanMapper() {
    }

    static String product(Product p) {
        return switch (p) {
            case MIS -> "INTRADAY";
            case CNC -> "CNC";
            case NRML -> "MARGIN";
        };
    }

    static Product product(String dhan) {
        return switch (dhan == null ? "" : dhan) {
            case "CNC", "MTF" -> Product.CNC;
            case "MARGIN" -> Product.NRML;
            default -> Product.MIS; // INTRADAY, CO, BO
        };
    }

    static String orderType(OrderType t) {
        return switch (t) {
            case MARKET -> "MARKET";
            case LIMIT -> "LIMIT";
            case SL -> "STOP_LOSS";
            case SL_M -> "STOP_LOSS_MARKET";
        };
    }

    static OrderType orderType(String dhan) {
        return switch (dhan == null ? "" : dhan) {
            case "LIMIT" -> OrderType.LIMIT;
            case "STOP_LOSS" -> OrderType.SL;
            case "STOP_LOSS_MARKET" -> OrderType.SL_M;
            default -> OrderType.MARKET;
        };
    }

    static Validity validity(String dhan) {
        return "IOC".equals(dhan) ? Validity.IOC : Validity.DAY;
    }

    static Side side(String dhan) {
        return "SELL".equals(dhan) ? Side.SELL : Side.BUY;
    }

    /** TRANSIT → PENDING; PENDING → OPEN (TRIGGER_PENDING for stop orders); PART_TRADED/TRIGGERED → OPEN; TRADED/CLOSED → COMPLETE; EXPIRED → CANCELLED. */
    static BrokerOrderStatus status(String dhan, OrderType type) {
        return switch (dhan == null ? "" : dhan.toUpperCase()) {
            case "TRANSIT" -> BrokerOrderStatus.PENDING;
            case "PENDING" -> type == OrderType.SL || type == OrderType.SL_M ? BrokerOrderStatus.TRIGGER_PENDING : BrokerOrderStatus.OPEN;
            case "PART_TRADED", "TRIGGERED" -> BrokerOrderStatus.OPEN;
            case "TRADED", "CLOSED" -> BrokerOrderStatus.COMPLETE;
            case "CANCELLED", "EXPIRED" -> BrokerOrderStatus.CANCELLED;
            case "REJECTED" -> BrokerOrderStatus.REJECTED;
            default -> BrokerOrderStatus.UNKNOWN;
        };
    }

    /** {@code yyyy-MM-dd HH:mm:ss[.S]} in IST; null for blanks and Dhan's zero dates. */
    static Instant time(String value, ZoneId zone) {
        if (value == null || value.isBlank() || value.startsWith("0001") || value.startsWith("1980") || "NA".equals(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), TIME).atZone(zone).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Quote {@code last_trade_time} ({@code dd/MM/yyyy HH:mm:ss}, IST); null for Dhan's 01/01/1980 placeholder. */
    static Instant quoteTime(String value, ZoneId zone) {
        if (value == null || value.isBlank() || value.startsWith("01/01/1980")) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), QUOTE_TIME).atZone(zone).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull() || v.asText().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(v.asText()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static BigDecimal positiveOrNull(JsonNode node, String field) {
        BigDecimal v = decimal(node, field);
        return v == null || v.signum() <= 0 ? null : v;
    }

    static Money money(JsonNode node, String field) {
        BigDecimal v = decimal(node, field);
        return Money.of(v == null ? BigDecimal.ZERO.setScale(2) : v);
    }

    static String textOrNull(JsonNode node, String field) {
        String v = node.path(field).asText("");
        return v.isBlank() || "null".equals(v) ? null : v;
    }

    /** A Dhan error body ({@code errorType, errorCode, errorMessage}); fields empty when the body is not one. */
    record DhanError(String type, String code, String message) {

        String describe(int status) {
            if (code.isEmpty() && message.isEmpty()) {
                return "Dhan HTTP " + status;
            }
            return (code.isEmpty() ? "" : code + " ") + message;
        }

        boolean dropsSession() {
            return SESSION_ERRORS.contains(code);
        }
    }

    static DhanError error(String body, ObjectMapper json) {
        try {
            JsonNode n = json.readTree(body == null ? "" : body);
            if (n != null && n.isObject()) {
                return new DhanError(n.path("errorType").asText(""), n.path("errorCode").asText(""), n.path("errorMessage").asText(""));
            }
        } catch (Exception ignored) {
            // not JSON
        }
        return new DhanError("", "", "");
    }

    /** Error code → kind (DH-9xx trading codes, 8xx data codes), else by HTTP status. */
    static BrokerException.Kind kind(DhanError e, int status) {
        switch (e.code()) {
            case "DH-901", "DH-902", "807", "808", "809", "810", "806" -> {
                return BrokerException.Kind.AUTH;
            }
            case "DH-904", "805" -> {
                return BrokerException.Kind.RATE_LIMIT;
            }
            case "DH-905", "DH-907", "804", "811", "812", "813", "814" -> {
                return BrokerException.Kind.INPUT;
            }
            case "DH-903", "DH-906" -> {
                return BrokerException.Kind.REJECTED;
            }
            case "DH-908", "DH-909", "800" -> {
                return BrokerException.Kind.NETWORK;
            }
            default -> {
                if (status == 429) {
                    return BrokerException.Kind.RATE_LIMIT;
                }
                if (status == 401 || status == 403) {
                    return BrokerException.Kind.AUTH;
                }
                if (status >= 500) {
                    return BrokerException.Kind.NETWORK;
                }
                if (status == 400 || status == 422) {
                    return BrokerException.Kind.INPUT;
                }
                return BrokerException.Kind.UNKNOWN;
            }
        }
    }

    static Map<String, Object> raw(JsonNode node, ObjectMapper json) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = json.convertValue(node, Map.class);
        return m == null ? Map.of() : m;
    }
}
