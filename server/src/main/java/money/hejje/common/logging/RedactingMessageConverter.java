package money.hejje.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Logback {@code %msg} replacement that masks secrets. Registered via {@code <conversionRule>} in logback-spring.xml. */
public class RedactingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogRedactor.redact(super.convert(event));
    }
}
