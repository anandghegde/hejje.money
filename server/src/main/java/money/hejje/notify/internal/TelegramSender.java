package money.hejje.notify.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import money.hejje.notify.Notification;
import money.hejje.notify.NotificationType;
import money.hejje.notify.NotifyProperties;
import org.springframework.stereotype.Component;

/** Telegram bot {@code sendMessage}; the token is only in the request URL and never in an error or log line. */
@Component
class TelegramSender implements money.hejje.notify.Channels.Sender {

    private final NotifyProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    TelegramSender(NotifyProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(properties.telegram().timeout()).build();
    }

    @Override
    public NotificationType.Channel channel() {
        return NotificationType.Channel.TELEGRAM;
    }

    @Override
    public boolean configured() {
        NotifyProperties.Telegram t = properties.telegram();
        return t.enabled() && t.botToken() != null && !t.botToken().isBlank() && t.chatId() != null && !t.chatId().isBlank();
    }

    @Override
    public String status() {
        if (!properties.telegram().enabled()) {
            return "disabled (hejje.notify.telegram.enabled)";
        }
        return configured() ? "configured (chat " + properties.telegram().chatId() + ")" : "missing HEJJE_TELEGRAM_BOT_TOKEN or chat-id";
    }

    @Override
    public int perMinute() {
        return properties.telegram().perMinute();
    }

    @Override
    public void sendDigest(java.util.List<Notification> held, java.time.Instant at) throws Exception {
        send(Digest.of(held, at));
    }

    @Override
    public void send(Notification n) throws Exception {
        NotifyProperties.Telegram t = properties.telegram();
        String text = "[" + n.severity() + "] " + n.title() + (n.body() == null || n.body().isBlank() ? "" : "\n" + n.body());
        String body = json.writeValueAsString(Map.of("chat_id", t.chatId(), "text", text, "disable_web_page_preview", true));
        HttpRequest request = HttpRequest.newBuilder(URI.create(t.baseUrl() + "/bot" + t.botToken() + "/sendMessage")).timeout(t.timeout())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Telegram unreachable: " + e.getClass().getSimpleName()); // no URL: it holds the token
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Telegram answered HTTP " + response.statusCode());
        }
    }
}
