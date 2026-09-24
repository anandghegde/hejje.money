package money.hejje.analytics.internal;

import money.hejje.analytics.ReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Completes trade causes once the post-exit window has passed (plan M9.6): every five minutes. */
@Component
class TradeCauseJob {

    private static final Logger log = LoggerFactory.getLogger(TradeCauseJob.class);

    private final ReviewService reviews;

    TradeCauseJob(ReviewService reviews) {
        this.reviews = reviews;
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    void complete() {
        int n = reviews.completeCauses();
        if (n > 0) {
            log.info("Completed the trade cause of {} reviews", n);
        }
    }
}
