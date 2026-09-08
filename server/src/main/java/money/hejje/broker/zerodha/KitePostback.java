package money.hejje.broker.zerodha;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Kite order postback checksum: {@code SHA-256(order_id + order_timestamp + api_secret)}. */
public final class KitePostback {

    private KitePostback() {
    }

    public static String checksum(String orderId, String orderTimestamp, String apiSecret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((orderId + orderTimestamp + apiSecret).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(String orderId, String orderTimestamp, String apiSecret, String presented) {
        if (orderId == null || orderTimestamp == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(checksum(orderId, orderTimestamp, apiSecret).getBytes(StandardCharsets.UTF_8),
                presented.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }
}
