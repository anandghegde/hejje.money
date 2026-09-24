package money.hejje.calibration.internal;

import money.hejje.calibration.CalibrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Labels the day's predictions from the stored candles after the evening refresh (plan M9.2). */
@Component
class CalibrationJob {

    private static final Logger log = LoggerFactory.getLogger(CalibrationJob.class);

    private final CalibrationService calibration;

    CalibrationJob(CalibrationService calibration) {
        this.calibration = calibration;
    }

    @Scheduled(cron = "0 45 18 * * MON-FRI", zone = "Asia/Kolkata")
    void nightly() {
        int n = calibration.labelDue();
        if (n > 0) {
            log.info("Labelled {} predictions for calibration", n);
        }
    }
}
