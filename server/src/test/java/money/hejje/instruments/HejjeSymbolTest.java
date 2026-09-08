package money.hejje.instruments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.OptionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HejjeSymbolTest {

    @ParameterizedTest
    @ValueSource(strings = {"NSE:RELIANCE", "INDEX:NIFTY 50", "NFO:NIFTY:FUT:2026-09-24", "NFO:NIFTY:OPT:2026-09-24:25000:CE",
            "NFO:RELIANCE:OPT:2026-09-29:1402.5:PE", "BSE:RELIANCE", "MCX:CRUDEOIL:FUT:2026-09-19"})
    void roundTrips(String text) {
        assertThat(HejjeSymbol.parse(text).format()).isEqualTo(text);
    }

    @Test
    void parsesEachForm() {
        HejjeSymbol eq = HejjeSymbol.parse("nse:reliance");
        assertThat(eq).isEqualTo(HejjeSymbol.equity(Exchange.NSE, "RELIANCE"));
        assertThat(eq.type()).isEqualTo(InstrumentType.EQ);

        HejjeSymbol index = HejjeSymbol.parse("INDEX:NIFTY 50");
        assertThat(index.type()).isEqualTo(InstrumentType.INDEX);
        assertThat(index.symbol()).isEqualTo("NIFTY 50");

        HejjeSymbol fut = HejjeSymbol.parse("NFO:NIFTY:FUT:2026-09-24");
        assertThat(fut.type()).isEqualTo(InstrumentType.FUT);
        assertThat(fut.expiry()).isEqualTo(LocalDate.of(2026, 9, 24));

        HejjeSymbol opt = HejjeSymbol.parse("NFO:NIFTY:OPT:2026-09-24:25000.00:ce");
        assertThat(opt.type()).isEqualTo(InstrumentType.OPT);
        assertThat(opt.strike()).isEqualByComparingTo(new BigDecimal("25000"));
        assertThat(opt.optionType()).isEqualTo(OptionType.CE);
        assertThat(opt.format()).isEqualTo("NFO:NIFTY:OPT:2026-09-24:25000:CE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "RELIANCE", "XYZ:RELIANCE", "NSE:NIFTY:FUT", "NFO:NIFTY:OPT:2026-09-24:25000",
            "NFO:NIFTY:FUT:24-09-2026", "NFO:NIFTY:OPT:2026-09-24:abc:CE", "NFO:NIFTY:OPT:2026-09-24:25000:XX",
            "NFO:NIFTY:CALL:2026-09-24:25000:CE", "INDEX:NIFTY:FUT:2026-09-24"})
    void rejectsMalformed(String text) {
        assertThatThrownBy(() -> HejjeSymbol.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void indexTypeRequiresIndexExchange() {
        assertThatThrownBy(() -> new HejjeSymbol(Exchange.NSE, "NIFTY 50", InstrumentType.INDEX, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
