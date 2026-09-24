package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestReviewsDecodeTheTradeCause(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/reviews" || r.URL.Query().Get("limit") != "5" {
			t.Errorf("unexpected request %s", r.URL)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[{"id":"r1","instrumentId":"i1","side":"BUY","quantity":10,"closedAt":"2026-09-23T04:40:00Z","netPnl":{"paise":-12000},
		  "outcomeR":-1.02,"closeReason":"STOP","cause":{"cause":"NOISE_STOP","entryTiming":"EARLY","mfeR":0.2,"maeR":-1.0,"jevCause":"NOISE_STOP",
		  "jevTiming":"GOOD","complete":true}},{"id":"r2","instrumentId":"i2","side":"SELL","quantity":5,"closedAt":"x","netPnl":{"paise":0},"cause":null}]`))
	}))
	defer srv.Close()
	rs, err := New(srv.URL, "k").Reviews(5)
	if err != nil {
		t.Fatal(err)
	}
	if len(rs) != 2 || rs[0].Cause == nil || rs[0].Cause.Cause != "NOISE_STOP" || *rs[0].Cause.EntryTiming != "EARLY" || !rs[0].Cause.Complete {
		t.Fatalf("bad decode %+v", rs[0].Cause)
	}
	if rs[1].Cause != nil {
		t.Fatalf("a review without a cause decodes one: %+v", rs[1].Cause)
	}
}
