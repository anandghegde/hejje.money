package ui

import (
	"fmt"
	"strings"
	"time"

	"hejje.money/tui/internal/api"
)

// Expiry renders the time left on an approval ("in 4m12s", "expired").
func Expiry(expiresAt string, now time.Time) string {
	t, err := time.Parse(time.RFC3339Nano, expiresAt)
	if err != nil {
		return expiresAt
	}
	d := t.Sub(now)
	if d <= 0 {
		return "expired"
	}
	return "in " + d.Round(time.Second).String()
}

// RenderApproval prints one approval: what approving does, the policy decision and the risk dry run.
func RenderApproval(a api.Approval, now time.Time) string {
	var b strings.Builder
	b.WriteString(fmt.Sprintf("APPROVAL %s  %s  %s\n", a.ID, a.Kind, a.Status))
	b.WriteString("  " + a.Summary + "\n")
	b.WriteString(fmt.Sprintf("  requested by %s · expires %s\n", a.RequestedBy, Expiry(a.ExpiresAt, now)))
	if a.Rationale != "" {
		b.WriteString("  why: " + a.Rationale + "\n")
	}
	if a.Policy != nil {
		b.WriteString(fmt.Sprintf("  policy %s (%s): %s\n", a.Policy.Decision, a.Policy.Rule, a.Policy.Reason))
	}
	if a.Risk != nil {
		b.WriteString(fmt.Sprintf("  risk dry run %s\n", a.Risk.Outcome))
		for _, c := range a.Risk.Checks {
			mark := "✓"
			if !c.Passed {
				mark = "✗"
			}
			line := "    " + mark + " " + c.Name
			if c.Message != "" && !c.Passed {
				line += " — " + c.Message
			}
			b.WriteString(line + "\n")
		}
	}
	if a.DecidedBy != "" {
		b.WriteString("  decided by " + a.DecidedBy + "\n")
	}
	if a.DecisionNote != "" {
		b.WriteString("  note: " + a.DecisionNote + "\n")
	}
	if id, ok := a.Result["orderId"]; ok {
		b.WriteString(fmt.Sprintf("  order %v %v\n", id, a.Result["state"]))
	}
	return b.String()
}

// RenderApprovalsTable lists approvals, newest first as the server returns them.
func RenderApprovalsTable(list []api.Approval, now time.Time) string {
	if len(list) == 0 {
		return "No approvals.\n"
	}
	var b strings.Builder
	b.WriteString(fmt.Sprintf("%-38s %-14s %-9s %-10s %s\n", "ID", "KIND", "STATUS", "EXPIRES", "SUMMARY"))
	for _, a := range list {
		expires := Expiry(a.ExpiresAt, now)
		if a.Status != "PENDING" {
			expires = "—"
		}
		b.WriteString(fmt.Sprintf("%-38s %-14s %-9s %-10s %s\n", a.ID, a.Kind, a.Status, expires, a.Summary))
	}
	return b.String()
}
