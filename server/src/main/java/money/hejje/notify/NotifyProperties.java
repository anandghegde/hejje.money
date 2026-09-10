package money.hejje.notify;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Notification settings ({@code hejje.notify.*}, docs/notifications.md).
 *
 * @param highScore      a signal at or above this Hejje Score is also a HIGH_SCORE_SETUP
 * @param eventLead      how far ahead a HIGH-risk market event is announced
 * @param dedupeWindow   a notification with the same key within this window is skipped
 * @param digestInterval how often rate-limited notifications are sent as one digest per channel
 */
@ConfigurationProperties("hejje.notify")
public record NotifyProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("80") int highScore,
        @DefaultValue("PT60M") Duration eventLead,
        @DefaultValue("PT10M") Duration dedupeWindow,
        @DefaultValue("PT5M") Duration digestInterval,
        @DefaultValue Email email,
        @DefaultValue Telegram telegram) {

    /** SMTP via {@code spring.mail.*}; {@code to} is a list of addresses. */
    public record Email(@DefaultValue("false") boolean enabled, String from, @DefaultValue({}) List<String> to, @DefaultValue("5") int perMinute) {}

    /** The bot token comes from {@code HEJJE_TELEGRAM_BOT_TOKEN} only (bound to {@code bot-token}); it is never returned by the API or logged. */
    public record Telegram(@DefaultValue("false") boolean enabled, @DefaultValue("https://api.telegram.org") String baseUrl, String botToken, String chatId,
            @DefaultValue("20") int perMinute, @DefaultValue("PT10S") Duration timeout) {}
}
