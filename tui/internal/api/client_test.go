package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestPlaceOrderSendsBearerAndIdempotencyKey(t *testing.T) {
	var gotAuth, gotKey string
	var gotBody PlaceOrderRequest
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		gotKey = r.Header.Get("Idempotency-Key")
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"id":"o1","state":"BROKER_ACCEPTED"}`))
	}))
	defer srv.Close()

	c := New(srv.URL, "hejje_abc_secret")
	o, err := c.PlaceOrder(PlaceOrderRequest{InstrumentID: "i1", Side: "BUY", Quantity: 10, OrderType: "MARKET", Product: "MIS", Reason: "MANUAL"})
	if err != nil {
		t.Fatalf("place order: %v", err)
	}
	if o.ID != "o1" || o.State != "BROKER_ACCEPTED" {
		t.Fatalf("unexpected order %+v", o)
	}
	if gotAuth != "Bearer hejje_abc_secret" {
		t.Fatalf("bad auth header %q", gotAuth)
	}
	if len(gotKey) != 36 {
		t.Fatalf("expected a UUID idempotency key, got %q", gotKey)
	}
	if gotBody.Quantity != 10 || gotBody.Side != "BUY" {
		t.Fatalf("bad body %+v", gotBody)
	}
}

func TestAPIErrorCarriesProblem(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/problem+json")
		w.WriteHeader(http.StatusUnprocessableEntity)
		_, _ = w.Write([]byte(`{"detail":"Order validation failed","reasons":["tick size"]}`))
	}))
	defer srv.Close()

	c := New(srv.URL, "k")
	_, err := c.PlaceOrder(PlaceOrderRequest{InstrumentID: "i", Side: "BUY", Quantity: 1, OrderType: "MARKET", Product: "MIS"})
	apiErr, ok := err.(*APIError)
	if !ok {
		t.Fatalf("expected APIError, got %T", err)
	}
	if apiErr.Status != 422 || len(apiErr.Reasons) != 1 || apiErr.Reasons[0] != "tick size" {
		t.Fatalf("unexpected api error %+v", apiErr)
	}
}

func TestIdempotencyKeysAreUnique(t *testing.T) {
	a, b := newIdempotencyKey(), newIdempotencyKey()
	if a == b {
		t.Fatal("keys must be unique")
	}
	if len(a) != 36 {
		t.Fatalf("bad key length %d", len(a))
	}
}

func TestBaseWSURL(t *testing.T) {
	if got := New("https://x.example", "k").BaseWSURL(); got != "wss://x.example" {
		t.Fatalf("wss: %q", got)
	}
	if got := New("http://localhost:8080", "k").BaseWSURL(); got != "ws://localhost:8080" {
		t.Fatalf("ws: %q", got)
	}
}
