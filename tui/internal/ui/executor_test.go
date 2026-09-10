package ui

import (
	"strings"
	"testing"

	"hejje.money/tui/internal/api"
)

func TestRenderExecutorRoles(t *testing.T) {
	four := int64(4)
	cases := map[string]api.ExecutorStatus{
		"ACTIVE vm-a (epoch 3)":                       {Instance: "vm-a", Role: "ACTIVE", Required: true, Epoch: 3},
		"STANDBY vm-a — active: vm-b (epoch 4)":       {Instance: "vm-a", Role: "STANDBY", Required: true, ActiveInstance: "vm-b", ActiveEpoch: &four},
		"STANDBY vm-a — active: none (lease expired)": {Instance: "vm-a", Role: "STANDBY", Required: true},
		"single instance vm-a (lease not required)":   {Instance: "vm-a", Role: "NOT_REQUIRED"},
	}
	for want, s := range cases {
		if got := strings.TrimSpace(RenderExecutor(s)); got != want {
			t.Errorf("got %q, want %q", got, want)
		}
	}
}
