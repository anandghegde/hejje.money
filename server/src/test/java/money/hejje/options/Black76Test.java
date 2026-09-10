package money.hejje.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import money.hejje.common.OptionType;
import org.junit.jupiter.api.Test;

/** Black-76 against values computed independently (Python, math.erf) and model identities (plan M5.4). */
class Black76Test {

    static final double T5 = 5.0 / 365;

    @Test
    void atTheMoneyNiftyWeeklyMatchesIndependentValues() {
        Black76.Greeks call = Black76.greeks(OptionType.CE, 25000, 25000, T5, 0.065, 0.15);
        assertThat(call.price()).isCloseTo(174.93939712896835, within(1e-6));
        assertThat(call.delta()).isCloseTo(0.5030537806122305, within(1e-9));
        assertThat(call.gamma()).isCloseTo(0.0009081065231939542, within(1e-12));
        assertThat(call.vega()).isCloseTo(11.662326924579888, within(1e-8));
        assertThat(call.theta()).isCloseTo(-17.46233679560029, within(1e-8));
        Black76.Greeks put = Black76.greeks(OptionType.PE, 25000, 25000, T5, 0.065, 0.15);
        assertThat(put.price()).isCloseTo(174.93939712896835, within(1e-6));
        assertThat(put.delta()).isCloseTo(-0.4960562047270718, within(1e-9));
    }

    @Test
    void outOfTheMoneyTextbookCaseMatchesIndependentValues() {
        assertThat(Black76.price(OptionType.CE, 100, 110, 0.5, 0.05, 0.2)).isCloseTo(2.1566505646011254, within(1e-9));
        assertThat(Black76.price(OptionType.PE, 100, 110, 0.5, 0.05, 0.2)).isCloseTo(11.909749684884439, within(1e-9));
        assertThat(Black76.greeks(OptionType.CE, 100, 110, 0.5, 0.05, 0.2).delta()).isCloseTo(0.2664317423533599, within(1e-9));
    }

    @Test
    void putCallParityOnTheForward() {
        double c = Black76.price(OptionType.CE, 24950, 25100, T5, 0.065, 0.14);
        double p = Black76.price(OptionType.PE, 24950, 25100, T5, 0.065, 0.14);
        assertThat(c - p).isCloseTo(Math.exp(-0.065 * T5) * (24950 - 25100), within(1e-6));
    }

    @Test
    void impliedVolatilityRoundTripsAndRejectsImpossiblePrices() {
        assertThat(Black76.impliedVol(OptionType.CE, 25000, 25100, T5, 0.065, 164.15534935011715)).isCloseTo(0.18, within(1e-7));
        // below the discounted intrinsic value of a deep in-the-money call: no volatility produces it
        assertThat(Black76.impliedVol(OptionType.CE, 25000, 24000, T5, 0.065, 900.0)).isNull();
        assertThat(Black76.impliedVol(OptionType.PE, 25000, 25000, T5, 0.065, 0.0)).isNull();
        assertThat(Black76.impliedVol(OptionType.CE, 25000, 25000, 0, 0.065, 100.0)).isNull();
    }

    @Test
    void normalCdfIsAccurate() {
        assertThat(Black76.cdf(0)).isEqualTo(0.5);
        assertThat(Black76.cdf(1.96)).isCloseTo(0.9750021048517796, within(1e-14));
        assertThat(Black76.cdf(-3.5)).isCloseTo(0.0002326290790355401, within(1e-15));
    }
}
