package money.hejje.notify.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import money.hejje.notify.Channels;
import money.hejje.notify.Notification;
import money.hejje.notify.NotificationType;
import money.hejje.notify.NotifyProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** The external channels against a stub Telegram API and a stub mail sender (plan M5.5). */
@SuppressWarnings("unchecked")
class ChannelSendersTest {

    static final String TOKEN = "123456:SECRET-token";

    WireMockServer telegram;

    @BeforeEach
    void start() {
        telegram = new WireMockServer(options().dynamicPort());
        telegram.start();
    }

    @AfterEach
    void stop() {
        telegram.stop();
    }

    static NotifyProperties props(String telegramUrl, String token, boolean emailEnabled) {
        return new NotifyProperties(true, 80, Duration.ofMinutes(60), Duration.ofMinutes(10), Duration.ofMinutes(5),
                new NotifyProperties.Email(emailEnabled, "hejje@example.com", List.of("me@example.com"), 5),
                new NotifyProperties.Telegram(true, telegramUrl, token, "4242", 20, Duration.ofSeconds(5)));
    }

    static Notification note(String title) {
        return new Notification(UUID.randomUUID(), NotificationType.KILL_SWITCH, NotificationType.Severity.CRITICAL, title, "no new orders", Map.of(), null,
                Instant.parse("2026-09-10T04:00:00Z"), null);
    }

    @Test
    void telegramPostsTheMessageToTheBotApi() throws Exception {
        telegram.stubFor(post(urlEqualTo("/bot" + TOKEN + "/sendMessage")).willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));
        TelegramSender sender = new TelegramSender(props(telegram.baseUrl(), TOKEN, false), new ObjectMapper());
        assertThat(sender.configured()).isTrue();
        assertThat(sender.status()).contains("4242").doesNotContain(TOKEN);
        sender.send(note("Kill switch activated (PAPER)"));
        telegram.verify(postRequestedFor(urlEqualTo("/bot" + TOKEN + "/sendMessage")).withRequestBody(
                equalToJson("{\"chat_id\":\"4242\",\"text\":\"[CRITICAL] Kill switch activated (PAPER)\\nno new orders\",\"disable_web_page_preview\":true}")));
    }

    @Test
    void aTelegramFailureNamesTheStatusButNeverTheToken() {
        telegram.stubFor(post(urlEqualTo("/bot" + TOKEN + "/sendMessage")).willReturn(aResponse().withStatus(401)));
        TelegramSender sender = new TelegramSender(props(telegram.baseUrl(), TOKEN, false), new ObjectMapper());
        assertThatThrownBy(() -> sender.send(note("x"))).hasMessage("Telegram answered HTTP 401");
        TelegramSender unreachable = new TelegramSender(props("http://127.0.0.1:1", TOKEN, false), new ObjectMapper());
        assertThatThrownBy(() -> unreachable.send(note("x"))).satisfies(e -> assertThat(e.getMessage()).startsWith("Telegram unreachable").doesNotContain(TOKEN));
    }

    @Test
    void telegramWithoutTheTokenIsNotConfigured() {
        TelegramSender sender = new TelegramSender(props(telegram.baseUrl(), "", false), new ObjectMapper());
        assertThat(sender.configured()).isFalse();
        assertThat(sender.status()).contains("HEJJE_TELEGRAM_BOT_TOKEN");
    }

    @Test
    void emailSendsOneMessageToTheRecipientsAndDigestsFoldSeveral() throws Exception {
        JavaMailSender mail = mock(JavaMailSender.class);
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mail);
        when(provider.getObject()).thenReturn(mail);
        EmailSender sender = new EmailSender(provider, props(telegram.baseUrl(), TOKEN, true));
        assertThat(sender.configured()).isTrue();
        sender.send(note("Kill switch activated (PAPER)"));
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("me@example.com");
        assertThat(sent.getValue().getFrom()).isEqualTo("hejje@example.com");
        assertThat(sent.getValue().getSubject()).isEqualTo("[Hejje CRITICAL] Kill switch activated (PAPER)");

        Channels.Sender asSender = sender;
        asSender.sendDigest(List.of(note("one"), note("two")), java.time.Instant.parse("2026-09-10T05:00:00Z"));
        verify(mail, org.mockito.Mockito.times(2)).send(sent.capture());
        assertThat(sent.getValue().getSubject()).contains("2 notification(s) held by the rate limit");
        assertThat(sent.getValue().getText()).contains("• [CRITICAL] one").contains("• [CRITICAL] two");
    }

    @Test
    void emailIsSkippedWhenDisabledOrWithoutSmtp() {
        ObjectProvider<JavaMailSender> none = mock(ObjectProvider.class);
        assertThat(new EmailSender(none, props(telegram.baseUrl(), TOKEN, true)).configured()).isFalse();
        assertThat(new EmailSender(none, props(telegram.baseUrl(), TOKEN, false)).status()).startsWith("disabled");
    }

    @Test
    void aChannelAtItsRateLimitHoldsForTheDigest() {
        assertThat(Channels.decide(false, 0, 5)).isEqualTo(Channels.Decision.SKIP);
        assertThat(Channels.decide(true, 4, 5)).isEqualTo(Channels.Decision.SEND);
        assertThat(Channels.decide(true, 5, 5)).isEqualTo(Channels.Decision.DIGEST);
    }
}
