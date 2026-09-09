package money.hejje.signals;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param enabled                 run the signal engine at startup
 * @param warmupDays              calendar days of candles loaded to warm indicators up when a runner starts
 * @param defaultValidityMinutes  signal validity when the definition has none; 0 = until the next bar close
 * @param expirySweep             how often ACTIVE signals past their validity are expired
 * @param inlineDispatch          process bus events on the publishing thread (tests) instead of the engine thread
 * @param defaultRiskRupees       money risked per trade when neither the deployment nor the definition says
 */
@ConfigurationProperties("hejje.signals")
public record SignalProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") int warmupDays,
        @DefaultValue("0") int defaultValidityMinutes,
        @DefaultValue("30s") Duration expirySweep,
        @DefaultValue("false") boolean inlineDispatch,
        @DefaultValue("2000") long defaultRiskRupees) {
}
