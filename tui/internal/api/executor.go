package api

import (
	"net/http"
	"time"
)

// ExecutorStatus is this instance's executor role and the lease as it is now (M5.6).
type ExecutorStatus struct {
	Instance       string     `json:"instance"`
	Role           string     `json:"role"`
	Required       bool       `json:"required"`
	Held           bool       `json:"held"`
	Epoch          int64      `json:"epoch"`
	ActiveInstance string     `json:"activeInstance"`
	ActiveEpoch    *int64     `json:"activeEpoch"`
	ExpiresAt      *time.Time `json:"expiresAt"`
	HoldUntil      *time.Time `json:"holdUntil"`
}

// FailoverResult is the answer of a controlled failover.
type FailoverResult struct {
	Released  bool      `json:"released"`
	Instance  string    `json:"instance"`
	Epoch     int64     `json:"epoch"`
	HoldUntil time.Time `json:"holdUntil"`
	Message   string    `json:"message"`
}

// Executor reports whether the connected instance is the active executor or a standby.
func (c *Client) Executor() (ExecutorStatus, error) {
	var s ExecutorStatus
	return s, c.do(http.MethodGet, "/server/executor", nil, false, &s)
}

// Failover releases the executor lease on the connected (active) instance so the standby takes over (admin).
func (c *Client) Failover() (FailoverResult, error) {
	var r FailoverResult
	return r, c.do(http.MethodPost, "/server/failover", map[string]string{"confirmation": "FAILOVER"}, false, &r)
}
