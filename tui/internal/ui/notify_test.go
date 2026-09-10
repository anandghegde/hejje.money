package ui

import (
	"strings"
	"testing"
	"time"

	"hejje.money/tui/internal/api"
)

func TestRenderNotificationsMarksUnread(t *testing.T) {
	now := time.Date(2026, 9, 10, 10, 0, 0, 0, time.UTC)
	read := now.Add(-time.Minute)
	out := RenderNotifications([]api.Notification{
		{Severity: "CRITICAL", Title: "Kill switch activated", CreatedAt: now.Add(-5 * time.Minute)},
		{Severity: "INFO", Title: "Signal: BUY NSE:INFY", CreatedAt: now.Add(-2 * time.Hour), ReadAt: &read},
	}, now)
	lines := strings.Split(strings.TrimSpace(out), "\n")
	if len(lines) != 2 || !strings.HasPrefix(lines[0], "• 5m ago") || !strings.Contains(lines[0], "Kill switch activated") {
		t.Fatalf("unexpected first line: %q", out)
	}
	if !strings.HasPrefix(lines[1], "  2h ago") {
		t.Fatalf("read notification should not be marked: %q", lines[1])
	}
	if RenderNotifications(nil, now) != "no notifications\n" {
		t.Fatal("empty inbox")
	}
}

func TestRenderNotificationTestListsEachChannel(t *testing.T) {
	out := RenderNotificationTest(api.NotificationTest{
		Notification: api.Notification{Title: "Test notification from Hejje"},
		Deliveries: []api.NotificationDelivery{
			{Channel: "IN_APP", Status: "SENT"},
			{Channel: "TELEGRAM", Status: "SKIPPED", Detail: "missing HEJJE_TELEGRAM_BOT_TOKEN or chat-id"},
		},
	})
	if !strings.Contains(out, "IN_APP    SENT\n") || !strings.Contains(out, "TELEGRAM  SKIPPED — missing HEJJE_TELEGRAM_BOT_TOKEN") {
		t.Fatalf("unexpected: %q", out)
	}
}
