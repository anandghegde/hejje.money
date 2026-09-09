package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestSignalCallsUseIdempotencyKeyOnlyForExecute(t *testing.T) {
	keys := map[string]string{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		keys[r.URL.Path] = r.Header.Get("Idempotency-Key")
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/api/v1/signals/s1/prepare":
			_, _ = w.Write([]byte(`{"signal":{"id":"s1","side":"BUY"},"proposal":{"side":"BUY","quantity":160,"orderType":"MARKET","product":"MIS","stopPrice":"1495.00","maxRisk":{"paise":200000}},"risk":{"outcome":"APPROVED","checks":[{"name":"killSwitch","passed":true,"message":"armed"}]},"sizing":{"quantity":160},"notes":[]}`))
		case "/api/v1/signals/s1/execute":
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"id":"o1","state":"BROKER_ACCEPTED","side":"BUY","quantity":160}`))
		case "/api/v1/signals/s1/skip":
			var body map[string]string
			_ = json.NewDecoder(r.Body).Decode(&body)
			_, _ = w.Write([]byte(`{"id":"s1","status":"SKIPPED","note":"` + body["reason"] + `"}`))
		case "/api/v1/today":
			_, _ = w.Write([]byte(`{"header":{"mode":"PAPER"},"best":{"instrument":"NSE:INFY","strategy":"orb","version":1,"score":87,"decision":"TRADE","direction":"BUY","signalId":"s1","entry":1507.5,"stop":1495,"target":1531,"riskRupees":2000,"supportingEvidence":["✓ close > opening_range_high(15m)"],"risks":[],"hardBlocks":[]},"ranked":[{"instrument":"NSE:INFY","strategy":"orb","version":1,"decision":"TRADE"}],"noTrade":null}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	c := New(srv.URL, "hejje_key")
	p, err := c.PrepareSignal("s1")
	if err != nil || p.Proposal.Quantity != 160 || p.Risk.Outcome != "APPROVED" {
		t.Fatalf("prepare: %v %+v", err, p)
	}
	o, err := c.ExecuteSignal("s1")
	if err != nil || o.ID != "o1" {
		t.Fatalf("execute: %v %+v", err, o)
	}
	s, err := c.SkipSignal("s1", "later")
	if err != nil || s.Status != "SKIPPED" || s.Note != "later" {
		t.Fatalf("skip: %v %+v", err, s)
	}
	if keys["/api/v1/signals/s1/prepare"] != "" || keys["/api/v1/signals/s1/skip"] != "" {
		t.Fatalf("prepare/skip must not carry an idempotency key: %v", keys)
	}
	if len(keys["/api/v1/signals/s1/execute"]) != 36 {
		t.Fatalf("execute must carry a UUID idempotency key: %q", keys["/api/v1/signals/s1/execute"])
	}
	today, err := c.Today()
	if err != nil || today.Best == nil || *today.Best.Score != 87 || today.Best.SignalID != "s1" || len(today.Ranked) != 1 {
		t.Fatalf("today: %v %+v", err, today)
	}
}
