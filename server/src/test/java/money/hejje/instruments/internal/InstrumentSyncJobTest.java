package money.hejje.instruments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import money.hejje.audit.AuditService;
import money.hejje.broker.BrokerAdapter;
import money.hejje.common.time.HejjeClock;
import money.hejje.common.time.MutableClock;
import money.hejje.instruments.InstrumentProperties;
import money.hejje.instruments.InstrumentSyncResult;
import org.junit.jupiter.api.Test;

class InstrumentSyncJobTest {

    @Test
    void storesMappingsUnderTheInstrumentBrokerCode() {
        BrokerAdapter broker = mock(BrokerAdapter.class);
        when(broker.brokerCode()).thenReturn("paper");
        when(broker.instrumentBrokerCode()).thenReturn("zerodha");
        when(broker.getInstruments()).thenReturn(List.of());
        InstrumentStore store = mock(InstrumentStore.class);
        HejjeClock clock = new HejjeClock(MutableClock.atIst("2026-09-09T08:00:00"), MutableClock.IST, (d, e) -> false);
        InstrumentSyncJob job = new InstrumentSyncJob(broker, store, mock(AuditService.class), clock, new InstrumentProperties(false, "-"));

        InstrumentSyncResult result = job.run();

        verify(store).upsertAll(eq("zerodha"), anyList(), any());
        verify(store).deactivateMissing(eq("zerodha"), any(), any());
        assertThat(result.broker()).isEqualTo("zerodha");
    }
}
