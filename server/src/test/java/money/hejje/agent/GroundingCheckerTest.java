package money.hejje.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import money.hejje.agent.internal.GroundingChecker;
import org.junit.jupiter.api.Test;

class GroundingCheckerTest {

    static final String ID = "0192f0c4-1f7a-7000-8000-000000000001";
    static final String OUTPUT = "{\"versionId\":\"" + ID + "\",\"score\":87,\"winRate\":0.55,\"riskRupees\":1500,\"entry\":24930.05,"
            + "\"evidence\":[\"expectancy 0.4237R over 212 trades\"]}";

    @Test
    void copiedRoundedAndPercentageNumbersAreVerified() {
        Grounding g = GroundingChecker.check("ORB v3 scores 87 [" + ID + "], wins 55% of the time, risks ₹1,500 at entry 24,930 "
                + "with expectancy 0.42R over 212 trades.", List.of(OUTPUT));
        assertThat(g.verifiedNumbers()).containsExactly("87", "55", "1,500", "24,930", "0.42", "212");
        assertThat(g.unverifiedNumbers()).isEmpty();
        assertThat(g.citedIds()).containsExactly(ID);
        assertThat(g.clean()).isTrue();
    }

    @Test
    void aFabricatedNumberAndAnUnknownIdAreFlagged() {
        String other = "0192f0c4-1f7a-7000-8000-00000000abcd";
        Grounding g = GroundingChecker.check("Score 95 [" + other + "], risk 1,500.", List.of(OUTPUT));
        assertThat(g.unverifiedNumbers()).containsExactly("95");
        assertThat(g.verifiedNumbers()).containsExactly("1,500");
        assertThat(g.unknownIds()).containsExactly(other);
        assertThat(g.clean()).isFalse();
    }

    @Test
    void datesTimesLabelsAndSmallCountsAreNotClaims() {
        Grounding g = GroundingChecker.check("1. Top 3 on 2026-09-10 at 10:42, valid until 2026-09-10T05:12:00Z; M5 bars, Q2 results, ORB v2.", List.of("{}"));
        assertThat(g.verifiedNumbers()).isEmpty();
        assertThat(g.unverifiedNumbers()).isEmpty();
    }

    @Test
    void signsAndIndianGroupingAreHandled() {
        Grounding g = GroundingChecker.check("A loss of 1,25,000 and a gain of +12.5.", List.of("{\"net\":-125000,\"best\":12.5}"));
        assertThat(g.verifiedNumbers()).containsExactly("1,25,000", "12.5");
        assertThat(g.unverifiedNumbers()).isEmpty();
    }
}
