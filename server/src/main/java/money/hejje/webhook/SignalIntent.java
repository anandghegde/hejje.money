package money.hejje.webhook;

import java.math.BigDecimal;

/**
 * What an external system asks for (PRD 59): an instrument and direction with a stop; never a raw order. Prices may be
 * JSON strings or numbers. {@code passphrase} and {@code timestamp} are only for PASSPHRASE webhooks.
 */
public record SignalIntent(String instrument, String direction, BigDecimal entry, BigDecimal stop, BigDecimal target, BigDecimal riskRupees, String note,
        String passphrase, String timestamp) {
}
