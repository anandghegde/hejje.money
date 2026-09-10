package api

import (
	"net/http"
	"strconv"
	"time"
)

// Notification is one entry of the in-app inbox (M5.5).
type Notification struct {
	ID        string     `json:"id"`
	Type      string     `json:"type"`
	Severity  string     `json:"severity"`
	Title     string     `json:"title"`
	Body      string     `json:"body"`
	CreatedAt time.Time  `json:"createdAt"`
	ReadAt    *time.Time `json:"readAt"`
}

// NotificationDelivery is one channel's attempt at a notification.
type NotificationDelivery struct {
	Channel string `json:"channel"`
	Status  string `json:"status"`
	Detail  string `json:"detail"`
}

// NotificationTest is the TEST notification and how each channel handled it.
type NotificationTest struct {
	Notification Notification           `json:"notification"`
	Deliveries   []NotificationDelivery `json:"deliveries"`
}

// Notifications lists the inbox, newest first.
func (c *Client) Notifications(limit int) ([]Notification, error) {
	var n []Notification
	return n, c.do(http.MethodGet, "/notifications?limit="+strconv.Itoa(limit), nil, false, &n)
}

// NotifyTest sends a TEST notification through every channel with an enabled TEST rule (admin).
func (c *Client) NotifyTest() (NotificationTest, error) {
	var t NotificationTest
	return t, c.do(http.MethodPost, "/notifications/test", nil, false, &t)
}
