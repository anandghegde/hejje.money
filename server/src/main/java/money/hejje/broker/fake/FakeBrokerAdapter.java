package money.hejje.broker.fake;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import money.hejje.broker.BrokerAdapter;
import money.hejje.broker.BrokerInstrument;
import money.hejje.broker.zerodha.KiteInstrumentCsv;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Deterministic in-memory broker for dev and test. Selected by {@code hejje.broker.adapter=fake} (the default).
 * M1.1: serves the instrument master from a checked-in Kite CSV fixture. Orders, quotes and session arrive in M1.2.
 */
@Component
@ConditionalOnProperty(name = "hejje.broker.adapter", havingValue = "fake", matchIfMissing = true)
public class FakeBrokerAdapter implements BrokerAdapter {

    public static final String BROKER_CODE = "fake";
    static final String FIXTURE = "broker/fake/kite-instruments-fixture.csv";

    @Override
    public String brokerCode() {
        return BROKER_CODE;
    }

    @Override
    public List<BrokerInstrument> getInstruments() {
        try (var reader = new InputStreamReader(new ClassPathResource(FIXTURE).getInputStream(), StandardCharsets.UTF_8)) {
            return KiteInstrumentCsv.parse(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
