package money.hejje.broker;

import java.util.UUID;
import money.hejje.common.Money;

/** Margin the broker would block for one prospective order. */
public record OrderMargin(UUID instrumentId, Money total, Money span, Money exposure, Money optionPremium, Money additional, Money charges) {
}
