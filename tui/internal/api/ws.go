package api

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/gorilla/websocket"
)

// StreamEvents connects to /ws/events and calls onLine for each event until the context is cancelled.
func StreamEvents(ctx context.Context, c *Client, onLine func(string)) error {
	u := fmt.Sprintf("%s/ws/events?token=%s", c.BaseWSURL(), c.apiKey)
	conn, _, err := websocket.DefaultDialer.DialContext(ctx, u, nil)
	if err != nil {
		return err
	}
	defer conn.Close()
	go func() {
		<-ctx.Done()
		_ = conn.Close()
	}()
	for {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		var event map[string]any
		if json.Unmarshal(msg, &event) == nil {
			onLine(fmt.Sprintf("%v", event))
		} else {
			onLine(string(msg))
		}
	}
}
