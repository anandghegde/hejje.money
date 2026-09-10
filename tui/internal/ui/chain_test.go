package ui

import (
	"encoding/json"
	"strings"
	"testing"

	"hejje.money/tui/internal/api"
)

func TestRenderChainMarksAtmAndShowsModelValues(t *testing.T) {
	var c api.OptionChain
	raw := `{"underlying":"NIFTY","expiry":"2026-09-15","forward":25010,"forwardSource":"NFO:NIFTY:FUT:2026-09-29","atmStrike":25000,"pcrOi":1.05,"maxPain":25000,
	 "rows":[{"strike":25000,"call":{"symbol":"c","last":120.3,"oi":80000,"iv":0.0966,"delta":0.5156},"put":{"symbol":"p","last":135.8,"oi":90000,"iv":0.118,"delta":-0.4854}},
	         {"strike":25100,"call":{"symbol":"c2","last":75.1,"oi":60000}}]}`
	if err := json.Unmarshal([]byte(raw), &c); err != nil {
		t.Fatal(err)
	}
	out := RenderChain(c)
	for _, want := range []string{"NIFTY 2026-09-15  forward 25010.00", "ATM 25000", "PCR(OI) 1.05", "max pain 25000", "* ", "9.7%", "0.52", "120.30", "-0.49", "90000"} {
		if !strings.Contains(out, want) {
			t.Fatalf("missing %q in\n%s", want, out)
		}
	}
}
