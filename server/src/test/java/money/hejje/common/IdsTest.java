package money.hejje.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void generatesVersion7Uuids() {
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void embedsTimestampAndOrdersByTime() {
        UUID earlier = Ids.newId(1_000_000L);
        UUID later = Ids.newId(1_000_001L);
        assertThat(Ids.timestampOf(earlier)).isEqualTo(1_000_000L);
        assertThat(earlier.toString().compareTo(later.toString())).isNegative();
    }

    @Test
    void correlationIdRoundTrips() {
        CorrelationId id = CorrelationId.newId();
        assertThat(CorrelationId.of(id.toString())).isEqualTo(id);
    }
}
