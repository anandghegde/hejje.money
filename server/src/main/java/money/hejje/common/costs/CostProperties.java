package money.hejje.common.costs;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Transaction cost rates ({@code hejje.costs.*}, sourced from config/costs.yaml). Percentages are fractions of turnover
 * (0.0003 = 0.03%). Defaults follow current Zerodha/NSE charge sheets and are marked to verify against the live sheets.
 *
 * @param verify           true until the rates are confirmed against the current charge sheets
 * @param brokerageFlat    per-order brokerage cap in rupees (Zerodha ₹20)
 * @param brokeragePct     percentage brokerage (0.0003 = 0.03%); the lower of flat and pct applies
 * @param gstPct           GST on brokerage + exchange txn + SEBI (0.18)
 * @param sebiPct          SEBI turnover charge (₹10 per crore = 0.000001)
 */
@ConfigurationProperties("hejje.costs")
public record CostProperties(
        @DefaultValue("true") boolean verify,
        @DefaultValue("20") BigDecimal brokerageFlat,
        @DefaultValue("0.0003") BigDecimal brokeragePct,
        @DefaultValue("0.18") BigDecimal gstPct,
        @DefaultValue("0.000001") BigDecimal sebiPct,
        @DefaultValue Segments equityIntraday,
        @DefaultValue Segments equityDelivery,
        @DefaultValue Segments futures,
        @DefaultValue Segments options) {

    /**
     * Per-segment rates.
     *
     * @param sttPct         securities transaction tax as a fraction of turnover
     * @param sttOnSellOnly  STT applies to the sell side only (true) or both sides (false, equity delivery)
     * @param exchangeTxnPct exchange transaction charge
     * @param stampDutyPct   stamp duty (buy side only)
     * @param brokerageFree  no brokerage for this segment (equity delivery)
     */
    public record Segments(
            @DefaultValue("0") BigDecimal sttPct,
            @DefaultValue("true") boolean sttOnSellOnly,
            @DefaultValue("0") BigDecimal exchangeTxnPct,
            @DefaultValue("0") BigDecimal stampDutyPct,
            @DefaultValue("false") boolean brokerageFree) {
    }

    public Segments forSegment(Segment segment) {
        return switch (segment) {
            case EQUITY_INTRADAY -> equityIntraday;
            case EQUITY_DELIVERY -> equityDelivery;
            case FUTURES -> futures;
            case OPTIONS -> options;
        };
    }
}
