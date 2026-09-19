package money.hejje.common.costs;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import money.hejje.common.InstrumentType;
import money.hejje.common.Product;
import money.hejje.common.Side;
import org.junit.jupiter.api.Test;

class CostModelTest {

    static CostProperties defaults() {
        return new CostProperties(true, new BigDecimal("20"), new BigDecimal("0.0003"), new BigDecimal("0.18"),
                new BigDecimal("0.000001"),
                new CostProperties.Segments(new BigDecimal("0.00025"), true, new BigDecimal("0.0000297"), new BigDecimal("0.00003"), false),
                new CostProperties.Segments(new BigDecimal("0.001"), false, new BigDecimal("0.0000297"), new BigDecimal("0.00015"), true),
                new CostProperties.Segments(new BigDecimal("0.0002"), true, new BigDecimal("0.0000173"), new BigDecimal("0.00002"), false),
                new CostProperties.Segments(new BigDecimal("0.001"), true, new BigDecimal("0.0003503"), new BigDecimal("0.00003"), false));
    }

    final CostModel model = new CostModel(defaults());

    @Test
    void equityIntradayBuyThenSell() {
        CostBreakdown buy = model.compute(new CostFill(InstrumentType.EQ, Product.MIS, Side.BUY, 100, new BigDecimal("1000.00")));
        assertThat(buy.brokerage().toRupeesString()).isEqualTo("20.00");
        assertThat(buy.stt().toRupeesString()).isEqualTo("0.00");
        assertThat(buy.exchangeTxn().toRupeesString()).isEqualTo("2.97");
        assertThat(buy.sebi().toRupeesString()).isEqualTo("0.10");
        assertThat(buy.gst().toRupeesString()).isEqualTo("4.15");
        assertThat(buy.stampDuty().toRupeesString()).isEqualTo("3.00");
        assertThat(buy.total().toRupeesString()).isEqualTo("30.22");

        CostBreakdown sell = model.compute(new CostFill(InstrumentType.EQ, Product.MIS, Side.SELL, 100, new BigDecimal("1010.00")));
        assertThat(sell.stt().toRupeesString()).isEqualTo("25.25");
        assertThat(sell.stampDuty().toRupeesString()).isEqualTo("0.00");
        assertThat(sell.total().toRupeesString()).isEqualTo("52.51");
    }

    @Test
    void equityDeliveryHasNoBrokerageAndBothSidesStt() {
        CostBreakdown buy = model.compute(new CostFill(InstrumentType.EQ, Product.CNC, Side.BUY, 10, new BigDecimal("1000.00")));
        assertThat(buy.brokerage().toRupeesString()).isEqualTo("0.00");
        assertThat(buy.stt().toRupeesString()).isEqualTo("10.00"); // 0.1% of 10000, both sides
    }

    @Test
    void futuresAndOptions() {
        CostBreakdown fut = model.compute(new CostFill(InstrumentType.FUT, Product.NRML, Side.SELL, 75, new BigDecimal("24980.00")));
        assertThat(fut.stt().toRupeesString()).isEqualTo("374.70"); // 0.02% of 1,873,500
        CostBreakdown opt = model.compute(new CostFill(InstrumentType.OPT, Product.NRML, Side.SELL, 75, new BigDecimal("200.00")));
        assertThat(opt.stt().toRupeesString()).isEqualTo("15.00"); // 0.1% of premium 15,000
        assertThat(opt.brokerage().toRupeesString()).isEqualTo("20.00"); // flat per executed order, not min(0.03% = 4.50, 20)
    }
}
