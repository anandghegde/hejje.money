package money.hejje.swing.internal;

import money.hejje.common.config.HejjeProperties;
import money.hejje.swing.SwingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The swing book's daily jobs: reconcile the holdings (M11.1) and the GTTs (M11.2) with the broker before the open and
 * after the close; after the close, trail the stops of positions that trail; before the open, load the day's setups
 * (M11.4); at the open, close the positions past their holding limit; weekly, the review of positions below entry.
 */
@Component
class SwingJobs {

    private static final Logger log = LoggerFactory.getLogger(SwingJobs.class);

    private final SwingService swing;
    private final SwingWatcher watcher;
    private final HejjeProperties properties;

    SwingJobs(SwingService swing, SwingWatcher watcher, HejjeProperties properties) {
        this.swing = swing;
        this.watcher = watcher;
        this.properties = properties;
    }

    @Scheduled(cron = "${hejje.swing.before-open-cron:0 0 9 * * MON-FRI}", zone = "Asia/Kolkata")
    void beforeOpen() {
        reconcile("before the open");
        try {
            watcher.prepare();
        } catch (RuntimeException e) {
            log.warn("Loading the swing setups failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "${hejje.swing.time-exit-cron:30 15 9 * * MON-FRI}", zone = "Asia/Kolkata")
    void timeExit() {
        try {
            int closed = swing.timeExit(properties.mode());
            if (closed > 0) {
                log.info("Closed {} swing positions past their holding limit at the open", closed);
            }
        } catch (RuntimeException e) {
            log.warn("Swing time exit failed: {}", e.getMessage());
        }
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
        try {
            swing.announceTimeExits(properties.mode());
        } catch (RuntimeException e) {
            log.warn("Announcing swing time exits failed: {}", e.getMessage());
        }
        reconcile("after the close");
    }

    @Scheduled(cron = "${hejje.swing.review-cron:0 50 15 * * FRI}", zone = "Asia/Kolkata")
    void weeklyReview() {
        try {
            swing.weeklyReview(properties.mode());
        } catch (RuntimeException e) {
            log.warn("Swing weekly review failed: {}", e.getMessage());
        }
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
