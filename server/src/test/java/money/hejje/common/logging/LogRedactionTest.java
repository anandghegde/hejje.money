package money.hejje.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LogRedactionTest {

    @Test
    void masksSecretValuesInManyShapes() {
        assertThat(LogRedactor.redact("access_token=abc123 next")).isEqualTo("access_token=*** next");
        assertThat(LogRedactor.redact("api_key: xyz, api_secret: 999")).isEqualTo("api_key: ***, api_secret: ***");
        assertThat(LogRedactor.redact("{\"password\":\"hunter2\",\"user\":\"bob\"}"))
                .isEqualTo("{\"password\":\"***\",\"user\":\"bob\"}");
        assertThat(LogRedactor.redact("Authorization: Bearer eyJhbGci.xxx")).isEqualTo("Authorization: ***");
        assertThat(LogRedactor.redact("ACCESS_TOKEN=abc&x=1")).isEqualTo("ACCESS_TOKEN=***&x=1");
        assertThat(LogRedactor.redact("nothing secret here")).isEqualTo("nothing secret here");
        assertThat(LogRedactor.redact(null)).isNull();
    }

    @Test
    void patternAppenderOutputIsMasked() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.getInstanceConverterMap().put("msg", RedactingMessageConverter::new);
        layout.setPattern("%msg%n");
        layout.start();
        LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<>();
        encoder.setContext(context);
        encoder.setLayout(layout);
        encoder.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(out);
        appender.start();
        Logger logger = context.getLogger("redaction-test");
        logger.addAppender(appender);
        try {
            logger.info("kite login returned access_token={} for user {}", "abc", "ZX123");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        String written = out.toString(StandardCharsets.UTF_8);
        assertThat(written).isEqualTo("kite login returned access_token=*** for user ZX123\n");
        assertThat(written).doesNotContain("abc");
    }

    @Test
    void jsonEncoderOutputIsMasked() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        LoggingEventCompositeJsonEncoder encoder = new LoggingEventCompositeJsonEncoder();
        encoder.setContext(context);
        encoder.getProviders().addProvider(new RedactingMessageJsonProvider());
        encoder.start();
        LoggingEvent event = new LoggingEvent("x", context.getLogger("json-test"), ch.qos.logback.classic.Level.INFO,
                "password=secret123 accepted", null, null);
        String written = new String(encoder.encode(event), StandardCharsets.UTF_8);
        assertThat(written).contains("\"message\":\"password=*** accepted\"").doesNotContain("secret123");
    }
}
