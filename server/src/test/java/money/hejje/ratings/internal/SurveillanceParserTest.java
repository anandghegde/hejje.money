package money.hejje.ratings.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** NSE's ASM and GSM reports as recorded on 2026-09-26 (trimmed to a few rows per code, test resources/surveillance). */
class SurveillanceParserTest {

    private final ObjectMapper json = new ObjectMapper();

    private JsonNode fixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/surveillance/" + name)) {
            return json.readTree(in);
        }
    }

    @Test
    void readsLongAndShortTermAsmStagesFromTheCode() throws Exception {
        SurveillanceParser.Report asm = SurveillanceParser.asm(fixture("reportASM.json"));
        assertThat(asm.date()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(asm.flags()).hasSize(12);
        assertThat(asm.flags()).extracting(SurveillanceParser.Flag::symbol, SurveillanceParser.Flag::flag).contains(
                tuple("NSE:A2ZINFRA", "ASM_LT_1"), tuple("NSE:ARVEE", "ASM_LT_2"),
                tuple("NSE:POLYSIL", "ASM_LT_3"), tuple("NSE:BLISSGVS", "ASM_LT_4"),
                tuple("NSE:ABH", "ASM_ST_1"), tuple("NSE:AHCL", "ASM_ST_2"));
        assertThat(asm.flags().get(0).code()).isEqualTo("LTASM - I (13)");
    }

    @Test
    void readsTheGsmStageWhereverTheCodeNamesIt() throws Exception {
        SurveillanceParser.Report gsm = SurveillanceParser.gsm(fixture("reportGSM.json"));
        assertThat(gsm.date()).isEqualTo(LocalDate.of(2026, 9, 25));
        Map<String, String> bySymbol = new HashMap<>();
        gsm.flags().forEach(f -> bySymbol.put(f.symbol(), f.flag()));
        assertThat(bySymbol).hasSize(13).containsEntry("NSE:ASIANTNE", "GSM_0").containsEntry("NSE:CBAZAAR", "GSM_1")
                .containsEntry("NSE:DIGJAMLMTD", "GSM_2").containsEntry("NSE:BLUECHIP", "GSM_3").containsEntry("NSE:UNIVAFOODS", "GSM_4")
                .containsEntry("NSE:ASIL", "GSM_6")
                // combined codes: IBC or ESM with GSM 0, GSM with IBC, long-term ASM with GSM 0
                .containsEntry("NSE:AGSTRA", "GSM_0").containsEntry("NSE:ANKITMETAL", "GSM_0").containsEntry("NSE:EUROTEXIND", "GSM_0")
                .containsEntry("NSE:CLCIND", "GSM_4").containsEntry("NSE:ORTEL", "GSM_3").containsEntry("NSE:AQYLON", "GSM_0");
    }

    @Test
    void mergeKeepsOneFlagPerSymbolGsmFirst() throws Exception {
        JsonNode gsm = json.readTree("[{\"symbol\":\"X\",\"survCode\":\"GSM - II (2)\",\"gsmTime\":\"25-Sep-2026 08:08:02\"}]");
        JsonNode asm = json.readTree("""
                {"longterm":{"data":[{"symbol":"X","survCode":"LTASM - I (13)"},{"symbol":"Y","survCode":"LTASM - III (15)"}]},
                 "shortterm":{"data":[{"symbol":"Y","survCode":"STASM - I (11)"},{"symbol":"Z","survCode":"STASM - II (12)"},{"symbol":"W","survCode":"NEW - I (1)"}]}}
                """);
        Map<String, SurveillanceParser.Flag> merged = SurveillanceParser.merge(SurveillanceParser.gsm(gsm), SurveillanceParser.asm(asm));
        assertThat(merged).hasSize(3); // W's unknown code is skipped
        assertThat(merged.get("NSE:X").flag()).isEqualTo("GSM_2");
        assertThat(merged.get("NSE:Y").flag()).isEqualTo("ASM_LT_3");
        assertThat(merged.get("NSE:Z").flag()).isEqualTo("ASM_ST_2");
    }

    @Test
    void aBlockPageOrAnotherShapeIsRejected() throws Exception {
        assertThatThrownBy(() -> SurveillanceParser.asm(json.readTree("{\"error\":\"denied\"}"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SurveillanceParser.gsm(json.readTree("{\"data\":[]}"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(SurveillanceParser.roman("IV")).isEqualTo(4);
        assertThat(SurveillanceParser.roman("VI")).isEqualTo(6);
        assertThat(SurveillanceParser.roman("0")).isZero();
    }
}
