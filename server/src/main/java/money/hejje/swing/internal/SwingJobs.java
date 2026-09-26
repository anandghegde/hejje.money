package money.hejje.swing.internal;

import money.hejje.common.config.HejjeProperties;
import money.hejje.swing.SwingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The swing book's daily jobs: reconcile the holdings (M11.1) and the GTTs (M11.2) with the broker before the open and
 * after the close; after the close, trail the stops of positions that trail.
 */
@Component
class SwingJobs {

    private static final Logger log = LoggerFactory.getLogger(SwingJobs.class);

    private final SwingService swing;
    private final HejjeProperties properties;

    SwingJobs(SwingService swing, HejjeProperties properties) {
        this.swing = swing;
        this.properties = properties;
    }

    @Scheduled(cron = "${hejje.swing.before-open-cron:0 0 9 * * MON-FRI}", zone = "Asia/Kolkata")
    void beforeOpen() {
        reconcile("before the open");
    }

    @Scheduled(cron = "${hejje.swing.after-close-cron:0 45 15 * * MON-FRI}", zone = "Asia/Kolkata")
    void afterClose() {
        try {
            int moved = swing.trail(properties.mode());
            if (moved > 0) {
                log.info("Trailed the stops of {} swing positions", moved);
            }
        } catch (RuntimeException e) {
            log.warn("Trailing swing stops failed: {}", e.getMessage());
        }
        reconcile("after the close");
    }

    private void reconcile(String when) {
        try {
            int issues = swing.reconcile().size();
            log.info("Swing reconciliation {}: {} open holdings and GTT issues", when, issues);
        } catch (RuntimeException e) {
            log.warn("Swing reconciliation {} failed: {}", when, e.getMessage());
        }
    }
}
