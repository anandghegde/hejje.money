package money.hejje.jev;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The Jev signal check ({@code hejje.jev.signal-check.*}, plan M9.5, docs/jev.md "Signal check").
 *
 * @param enabled   ask Jev about every strategy signal (needs {@code hejje.jev.enabled}); off by default
 * @param gate      {@code off} (annotate only), {@code caution} (a disagreement adds {@code JEV_DISAGREES} to the
 *                  recommendation's cautions) or {@code approval} (it also turns an AUTO execution into an approval).
 *                  Anything above {@code off} is refused at startup until calibration passes for {@code signal-check}
 * @param questionSet the stage-2 question set the check asks
 */
@ConfigurationProperties("hejje.jev.signal-check")
public record SignalCheckProperties(@DefaultValue("false") boolean enabled, @DefaultValue("off") String gate, @DefaultValue("bot-stage2") String questionSet) {}
