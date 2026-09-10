package money.hejje.broker.dhan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import money.hejje.broker.BrokerInstrument;
import money.hejje.common.Exchange;
import money.hejje.common.InstrumentType;
import money.hejje.common.OptionType;
import org.junit.jupiter.api.Test;

class DhanInstrumentCsvTest {

    static final String HEADER = "SEM_EXM_EXCH_ID,SEM_SEGMENT,SEM_SMST_SECURITY_ID,SEM_INSTRUMENT_NAME,SEM_EXPIRY_CODE,SEM_TRADING_SYMBOL,SEM_LOT_UNITS,"
            + "SEM_CUSTOM_SYMBOL,SEM_EXPIRY_DATE,SEM_STRIKE_PRICE,SEM_OPTION_TYPE,SEM_TICK_SIZE,SEM_EXPIRY_FLAG,SEM_EXCH_INSTRUMENT_TYPE,SEM_SERIES,SM_SYMBOL_NAME";
    static final String CSV = HEADER + "\n"
            + "NSE,E,1594,EQUITY,,INFY,1.0,Infosys,,-0.01000,XX,5.0000,NA,ES,EQ,INFOSYS LIMITED\n"
            + "NSE,E,99999,EQUITY,,SOMEBE,1.0,Some BE,,-0.01000,XX,5.0000,NA,ES,BE,SOME BE\n"
            + "NSE,I,13,INDEX,,NIFTY,1.0,Nifty 50,,-0.01000,XX,5.0000,NA,INDEX,,NIFTY\n"
            + "NSE,D,52175,FUTIDX,0,NIFTY-Sep2026-FUT,75.0,NIFTY SEP FUT,2026-09-29 14:30:00,-0.01000,XX,10.0000,M,FUTIDX,,NIFTY\n"
            + "NSE,D,60001,OPTIDX,0,NIFTY-Sep2026-25000-CE,75.0,NIFTY 29 SEP 25000 CALL,2026-09-29 14:30:00,25000.00000,CE,5.0000,M,OPTIDX,,NIFTY\n"
            + "BSE,E,500209,EQUITY,,INFY,1.0,Infosys,,-0.01000,XX,5.0000,NA,ES,A,INFOSYS LIMITED\n"
            + "MCX,M,40000,FUTCOM,0,GOLD-Oct2026-FUT,1.0,GOLD OCT FUT,2026-10-05 23:30:00,-0.01000,XX,100.0000,M,FUTCOM,,GOLD\n"
            + "BSE,C,1026077,FUTCUR,0,USDINR-28Aug2024-FUT,1.0,USDINR AUG FUT,2024-08-28 14:30:00,-0.01000,XX,0.2500,M,FUTCUR,,USDINR\n";

    @Test
    void rowsMapOntoHejjeSymbolsWithCompositeTokens() {
        List<BrokerInstrument> rows = DhanInstrumentCsv.parse(new ByteArrayInputStream(CSV.getBytes(StandardCharsets.UTF_8)));
        assertThat(rows).extracting(BrokerInstrument::brokerToken)
                .containsExactly("NSE_EQ:1594", "IDX_I:13", "NSE_FNO:52175", "NSE_FNO:60001", "BSE_EQ:500209");

        BrokerInstrument infy = rows.get(0);
        assertThat(infy.exchange()).isEqualTo(Exchange.NSE);
        assertThat(infy.type()).isEqualTo(InstrumentType.EQ);
        assertThat(infy.symbol()).isEqualTo("INFY");
        assertThat(infy.exchangeSegment()).isEqualTo("NSE_EQ");
        assertThat(infy.tickSize()).isEqualByComparingTo("0.05"); // SEM_TICK_SIZE is in paise

        BrokerInstrument nifty = rows.get(1);
        assertThat(nifty.exchange()).isEqualTo(Exchange.INDEX);
        assertThat(nifty.symbol()).isEqualTo("NIFTY 50"); // the Kite name Hejje uses

        BrokerInstrument fut = rows.get(2);
        assertThat(fut.exchange()).isEqualTo(Exchange.NFO);
        assertThat(fut.symbol()).isEqualTo("NIFTY");
        assertThat(fut.underlying()).isEqualTo("NIFTY");
        assertThat(fut.expiry()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(fut.lotSize()).isEqualTo(75);
        assertThat(fut.tickSize()).isEqualByComparingTo("0.10");

        BrokerInstrument call = rows.get(3);
        assertThat(call.type()).isEqualTo(InstrumentType.OPT);
        assertThat(call.strike()).isEqualTo(new BigDecimal("25000.00"));
        assertThat(call.optionType()).isEqualTo(OptionType.CE);

        assertThat(rows.get(4).exchange()).isEqualTo(Exchange.BSE);
    }

    @Test
    void anEmptyFileHasNoInstruments() {
        assertThat(DhanInstrumentCsv.parse(new ByteArrayInputStream(new byte[0]))).isEmpty();
        assertThat(DhanInstrumentCsv.parse(new ByteArrayInputStream(HEADER.getBytes(StandardCharsets.UTF_8)))).isEmpty();
    }
}
