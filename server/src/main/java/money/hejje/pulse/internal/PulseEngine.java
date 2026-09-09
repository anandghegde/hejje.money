package money.hejje.pulse.internal;

import java.time.Instant;
import java.time.LocalDate;
import money.hejje.pulse.PulseProperties;
import money.hejje.pulse.PulseSnapshot;
import org.springframework.stereotype.Component;

/** Loads the inputs and applies the rules. */
@Component
public class PulseEngine {

    private final PulseInputs inputs;
    private final PulseProperties props;

    PulseEngine(PulseInputs inputs, PulseProperties props) {
        this.inputs = inputs;
        this.props = props;
    }

    public PulseSnapshot snapshot(LocalDate date, Instant asOf) {
        PulseInput in = inputs.load(date, asOf);
        return new PulseSnapshot(date, asOf, PulseRules.technical(in, props), PulseRules.market(in, props));
    }
}
