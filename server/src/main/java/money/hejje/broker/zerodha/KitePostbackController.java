package money.hejje.broker.zerodha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zerodhatech.models.Order;
import java.util.Optional;
import money.hejje.broker.BrokerOrderUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kite order postback (public endpoint, per-IP rate limited by the auth filter). Accepts the order JSON, verifies the
 * checksum with the api secret and republishes the order as a {@link BrokerOrderUpdate}. Active only when a Kite api
 * secret is configured; the update is ignored (204) unless the Zerodha adapter is the active adapter.
 */
@RestController
@RequestMapping("/api/v1/broker/postback")
@ConditionalOnExpression("'${hejje.broker.zerodha.api-secret:}' != ''")
class KitePostbackController {

    private static final Logger log = LoggerFactory.getLogger(KitePostbackController.class);

    private final KiteProperties properties;
    private final Optional<ZerodhaKiteAdapter> adapter;
    private final ObjectMapper json;

    KitePostbackController(KiteProperties properties, Optional<ZerodhaKiteAdapter> adapter, ObjectMapper json) {
        this.properties = properties;
        this.adapter = adapter;
        this.json = json;
    }

    @PostMapping(consumes = {"application/json", "application/x-www-form-urlencoded", "text/plain"})
    ResponseEntity<Void> postback(@RequestBody String body) {
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        String orderId = text(node, "order_id");
        String orderTimestamp = text(node, "order_timestamp");
        String checksum = text(node, "checksum");
        if (!KitePostback.verify(orderId, orderTimestamp, properties.apiSecret(), checksum)) {
            log.warn("Rejected Kite postback with bad checksum for order {}", orderId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Order order = KiteMapper.orderFromJson(body);
        adapter.ifPresent(a -> a.publishOrderUpdate(order, BrokerOrderUpdate.Source.BROKER_POSTBACK));
        return ResponseEntity.noContent().build();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
