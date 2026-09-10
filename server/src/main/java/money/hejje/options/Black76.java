package money.hejje.options;

import money.hejje.common.OptionType;

/**
 * Black-76 on the futures (forward) price, the model for options on index futures (plan M5.4). Time in years, rates and
 * volatility annual and continuous. Pure and deterministic; the normal CDF is West's double-precision algorithm.
 */
public final class Black76 {

    private Black76() {
    }

    /** Price and sensitivities. {@code vega} is per 1 volatility point (0.01), {@code theta} per calendar day. */
    public record Greeks(double price, double delta, double gamma, double vega, double theta) {}

    public static double price(OptionType type, double forward, double strike, double years, double rate, double vol) {
        return greeks(type, forward, strike, years, rate, vol).price();
    }

    public static Greeks greeks(OptionType type, double forward, double strike, double years, double rate, double vol) {
        if (forward <= 0 || strike <= 0 || years <= 0 || vol <= 0) {
            throw new IllegalArgumentException("forward, strike, time and volatility must be positive");
        }
        double sqrtT = Math.sqrt(years);
        double d1 = (Math.log(forward / strike) + vol * vol * years / 2) / (vol * sqrtT);
        double d2 = d1 - vol * sqrtT;
        double df = Math.exp(-rate * years);
        double pdf = Math.exp(-d1 * d1 / 2) / Math.sqrt(2 * Math.PI);
        double price;
        double delta;
        if (type == OptionType.CE) {
            price = df * (forward * cdf(d1) - strike * cdf(d2));
            delta = df * cdf(d1);
        } else {
            price = df * (strike * cdf(-d2) - forward * cdf(-d1));
            delta = -df * cdf(-d1);
        }
        double gamma = df * pdf / (forward * vol * sqrtT);
        double vega = forward * df * pdf * sqrtT / 100;
        double thetaYear = -forward * df * pdf * vol / (2 * sqrtT) + rate * price;
        return new Greeks(price, delta, gamma, vega, thetaYear / 365);
    }

    /**
     * The volatility that reproduces {@code marketPrice} (bisection on the monotonic price), or null when the price is
     * outside what the model can produce (below the discounted intrinsic value or above the discounted forward/strike).
     */
    public static Double impliedVol(OptionType type, double forward, double strike, double years, double rate, double marketPrice) {
        if (marketPrice <= 0 || years <= 0 || forward <= 0 || strike <= 0) {
            return null;
        }
        double df = Math.exp(-rate * years);
        double intrinsic = df * Math.max(0, type == OptionType.CE ? forward - strike : strike - forward);
        double cap = df * (type == OptionType.CE ? forward : strike);
        if (marketPrice < intrinsic - 1e-9 || marketPrice >= cap) {
            return null;
        }
        double lo = 1e-4;
        double hi = 5.0;
        if (price(type, forward, strike, years, rate, hi) < marketPrice) {
            return null;
        }
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (price(type, forward, strike, years, rate, mid) < marketPrice) {
                lo = mid;
            } else {
                hi = mid;
            }
            if (hi - lo < 1e-10) {
                break;
            }
        }
        return (lo + hi) / 2;
    }

    /** Standard normal CDF (W. J. West, "Better approximations to cumulative normal functions", double precision). */
    public static double cdf(double x) {
        double z = Math.abs(x);
        double c;
        if (z > 37) {
            c = 0;
        } else {
            double e = Math.exp(-z * z / 2);
            if (z < 7.07106781186547) {
                double n = 3.52624965998911e-02 * z + 0.700383064443688;
                n = n * z + 6.37396220353165;
                n = n * z + 33.912866078383;
                n = n * z + 112.079291497871;
                n = n * z + 221.213596169931;
                n = n * z + 220.206867912376;
                double d = 8.83883476483184e-02 * z + 1.75566716318264;
                d = d * z + 16.064177579207;
                d = d * z + 86.7807322029461;
                d = d * z + 296.564248779674;
                d = d * z + 637.333633378831;
                d = d * z + 793.826512519948;
                d = d * z + 440.413735824752;
                c = e * n / d;
            } else {
                double b = z + 0.65;
                b = z + 4 / b;
                b = z + 3 / b;
                b = z + 2 / b;
                b = z + 1 / b;
                c = e / b / 2.506628274631;
            }
        }
        return x > 0 ? 1 - c : c;
    }
}
