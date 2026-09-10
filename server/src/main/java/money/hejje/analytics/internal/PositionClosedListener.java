package money.hejje.analytics.internal;

import money.hejje.analytics.ReviewService;
import money.hejje.orders.PositionChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** A position going flat triggers its post-trade review (idempotent per entry order), then a drift evaluation of its deployment (M5.1). */
@Component
class PositionClosedListener {

    private static final Logger log = LoggerFactory.getLogger(PositionClosedListener.class);

    private final ReviewService reviews;
    private final money.hejje.analytics.drift.DriftService drift;

    PositionClosedListener(ReviewService reviews, money.hejje.analytics.drift.DriftService drift) {
        this.reviews = reviews;
        this.drift = drift;
    }

    @ApplicationModuleListener
    void onPositionChanged(PositionChangedEvent event) {
        if (event.netQuantity() != 0) {
            return;
        }
        try {
            reviews.reviewClosedPosition(event.positionId()).ifPresent(drift::onReview);
        } catch (RuntimeException e) {
            log.warn("Post-trade review for position {} failed", event.positionId(), e);
        }
    }
}
