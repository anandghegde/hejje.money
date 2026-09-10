package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"hejje.money/tui/internal/api"
)

func TestAiInteractiveContinuesTheConversation(t *testing.T) {
	var bodies []map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/api/v1/agents/ai/status":
			_, _ = w.Write([]byte(`{"enabled":true,"profile":"reasoning"}`))
		case "/api/v1/agents/ai/ask":
			if r.Header.Get("Accept") != "application/json" {
				t.Errorf("ask must accept JSON, got %q", r.Header.Get("Accept"))
			}
			var body map[string]any
			_ = json.NewDecoder(r.Body).Decode(&body)
			bodies = append(bodies, body)
			_, _ = w.Write([]byte(`{"conversationId":"c1","answer":"Trending up [get_market_regime].","steps":2,"profile":"reasoning",
				"grounding":{"verifiedNumbers":[],"unverifiedNumbers":[]},"trace":[{"tool":"get_market_regime","status":"OK","requiredScope":"market:read"}]}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	client = api.New(srv.URL, "hejje_test_key")

	var out bytes.Buffer
	if err := aiInteractive(strings.NewReader("What is the regime?\nAnd tomorrow?\n/new\nAgain?\n/quit\n"), &out); err != nil {
		t.Fatal(err)
	}
	if len(bodies) != 3 {
		t.Fatalf("expected 3 questions, got %d", len(bodies))
	}
	if _, ok := bodies[0]["conversationId"]; ok {
		t.Fatalf("first question must start a conversation: %v", bodies[0])
	}
	if bodies[1]["conversationId"] != "c1" {
		t.Fatalf("follow-up must continue c1: %v", bodies[1])
	}
	if _, ok := bodies[2]["conversationId"]; ok {
		t.Fatalf("/new must start a new conversation: %v", bodies[2])
	}
	if !strings.Contains(out.String(), "Trending up [get_market_regime].") || !strings.Contains(out.String(), "get_market_regime") {
		t.Fatalf("unexpected output\n%s", out.String())
	}
}
