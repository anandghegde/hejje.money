package ui

import (
	"fmt"

	"hejje.money/tui/internal/api"
)

// RenderExecutor is one line: this instance's role and who is active.
func RenderExecutor(s api.ExecutorStatus) string {
	switch s.Role {
	case "NOT_REQUIRED":
		return fmt.Sprintf("single instance %s (lease not required)\n", s.Instance)
	case "ACTIVE":
		return fmt.Sprintf("ACTIVE %s (epoch %d)\n", s.Instance, s.Epoch)
	}
	active := "none (lease expired)"
	if s.ActiveInstance != "" && s.ActiveEpoch != nil {
		active = fmt.Sprintf("%s (epoch %d)", s.ActiveInstance, *s.ActiveEpoch)
	}
	line := fmt.Sprintf("STANDBY %s — active: %s", s.Instance, active)
	if s.HoldUntil != nil {
		line += "; holding back until " + s.HoldUntil.Local().Format("15:04:05")
	}
	return line + "\n"
}
