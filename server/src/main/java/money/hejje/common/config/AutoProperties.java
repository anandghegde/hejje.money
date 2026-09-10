package money.hejje.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AUTO execution settings ({@code hejje.auto.*}, plan M5.2, docs/execution.md "AUTO mode").
 *
 * @param acknowledged           must be true for {@code hejje.mode=AUTO} (besides the prod profile)
 * @param minPaperTrades         closed paper trades a version needs before an AUTO deployment at autonomy 4-5
 * @param defaultMaxTradesPerDay per-deployment daily entry budget at autonomy 4-5 (deployment param {@code daily_max_trades})
 * @param defaultMaxLossRupees   per-deployment daily realized-loss budget (deployment param {@code daily_max_loss_rupees})
 * @param selfPauseDrift         the drift status at which an autonomy-5 deployment pauses itself
 */
@ConfigurationProperties("hejje.auto")
public record AutoProperties(
        @DefaultValue("false") boolean acknowledged,
        @DefaultValue("30") int minPaperTrades,
        @DefaultValue("3") int defaultMaxTradesPerDay,
        @DefaultValue("5000") long defaultMaxLossRupees,
        @DefaultValue("DEGRADING") String selfPauseDrift) {
}
