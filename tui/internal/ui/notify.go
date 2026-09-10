package ui

import (
	"fmt"
	"strings"
	"time"

	"hejje.money/tui/internal/api"
)

// RenderNotifications is the inbox as lines: time, severity, title (unread marked with •).
func RenderNotifications(list []api.Notification, now time.Time) string {
	if len(list) == 0 {
		return "no notifications\n"
	}
	var b strings.Builder
	for _, n := range list {
		mark := " "
		if n.ReadAt == nil {
			mark = "•"
		}
		fmt.Fprintf(&b, "%s %-8s %-9s %s\n", mark, ago(now, n.CreatedAt), n.Severity, n.Title)
	}
	return b.String()
}

// RenderNotificationTest shows each channel's delivery of the TEST notification.
func RenderNotificationTest(t api.NotificationTest) string {
	var b strings.Builder
	fmt.Fprintf(&b, "sent %q\n", t.Notification.Title)
	for _, d := range t.Deliveries {
		line := fmt.Sprintf("  %-9s %s", d.Channel, d.Status)
		if d.Detail != "" {
			line += " — " + d.Detail
		}
		b.WriteString(line + "\n")
	}
	return b.String()
}

func ago(now, t time.Time) string {
	d := now.Sub(t)
	switch {
	case d < time.Minute:
		return "now"
	case d < time.Hour:
		return fmt.Sprintf("%dm ago", int(d.Minutes()))
	case d < 24*time.Hour:
		return fmt.Sprintf("%dh ago", int(d.Hours()))
	default:
		return t.Format("Jan 02")
	}
}
