package money.hejje.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import net.logstash.logback.composite.loggingevent.MessageJsonProvider;

/** JSON {@code message} field provider that masks secrets (used by the prod JSON encoder). */
public class RedactingMessageJsonProvider extends MessageJsonProvider {

    @Override
    public void writeTo(JsonGenerator generator, ILoggingEvent event) throws IOException {
        generator.writeStringField(getFieldName(), LogRedactor.redact(event.getFormattedMessage()));
    }
}
