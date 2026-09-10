package money.hejje.execution;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import money.hejje.auth.internal.ClientCredentialService;
import money.hejje.common.security.HejjePrincipal;
import money.hejje.common.security.ScopeCatalog;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared setup of the basket and split ITs (plan M5.3). */
final class PlanningITSupport {

    private PlanningITSupport() {
    }

    static void reset(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE basket_leg, basket, split_order, trade, order_event, hejje_order, risk_decision, order_intent, position, idempotency_record CASCADE");
        jdbc.update("UPDATE kill_switch SET stop_new_orders = FALSE, set_at = NULL, set_by = NULL, reason = NULL");
        jdbc.update("UPDATE risk_limits SET max_loss_per_day_paise = 500000, max_realized_loss_paise = 500000, max_total_loss_paise = 750000, "
                + "max_margin_utilization_pct = 80.00, max_open_positions = 5, max_trades_per_day = 20, max_risk_per_trade_paise = 200000, "
                + "max_quantity = 1000, max_notional_paise = 50000000, min_reward_risk = 1.00, mandatory_stop = TRUE, max_stop_distance_pct = 5.00, "
                + "no_new_trades_after = '14:45', no_averaging_down = TRUE, no_reentry_minutes = 10, max_consecutive_losses = 3 WHERE mode = 'PAPER'");
    }

    /** A dedicated key for transactional REST calls: the admin's transactional rate bucket is shared with other ITs. */
    static String executionKey(ClientCredentialService clients) {
        HejjePrincipal actor = new HejjePrincipal(UUID.randomUUID(), "admin", HejjePrincipal.Type.USER, ScopeCatalog.ALL);
        return clients.create("planning-" + UUID.randomUUID(), Set.of(ScopeCatalog.MARKET_READ, ScopeCatalog.ORDERS_EXECUTE, ScopeCatalog.ORDERS_CANCEL), null, actor)
                .key();
    }

    static <T> T await(Supplier<Optional<T>> probe, String what) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Optional<T> v = probe.get();
            if (v.isPresent()) {
                return v.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
