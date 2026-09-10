package api

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestApproveAndRejectAreIdempotentPosts(t *testing.T) {
	var paths, keys []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("expected POST, got %s", r.Method)
		}
		paths = append(paths, r.URL.Path)
		keys = append(keys, r.Header.Get("Idempotency-Key"))
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"id":"a1","status":"APPROVED","result":{"orderId":"o1","state":"FILLED"}}`))
	}))
	defer srv.Close()
	c := New(srv.URL, "hejje_k")
	a, err := c.Approve("a1")
	if err != nil || a.Status != "APPROVED" || a.Result["orderId"] != "o1" {
		t.Fatalf("approve: %v %+v", err, a)
	}
	if _, err := c.Reject("a1", "not today"); err != nil {
		t.Fatal(err)
	}
	if paths[0] != "/api/v1/approvals/a1/approve" || paths[1] != "/api/v1/approvals/a1/reject" {
		t.Fatalf("paths %v", paths)
	}
	if len(keys[0]) != 36 || keys[0] == keys[1] {
		t.Fatalf("expected distinct UUID idempotency keys, got %v", keys)
	}
}
