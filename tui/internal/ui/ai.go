package ui

import (
	"fmt"
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"

	"hejje.money/tui/internal/api"
)

var numberToken = regexp.MustCompile(`[-+]?\d[\d,]*(?:\.\d+)?`)

// RenderAiTurn prints a Hejje AI answer with unverified numbers marked, the grounding note and the tool trace.
func RenderAiTurn(t api.AiTurn) string {
	var b strings.Builder
	b.WriteString(MarkUnverified(t.Answer, t.Grounding.UnverifiedNumbers))
	b.WriteString("\n\n")
	flagged := append(append([]string{}, t.Grounding.UnverifiedNumbers...), t.Grounding.UnknownIDs...)
	if len(flagged) > 0 {
		b.WriteString(fmt.Sprintf("⚠ unverified (not in this turn's tool results): %s\n", strings.Join(flagged, ", ")))
	} else {
		b.WriteString(fmt.Sprintf("✓ %d number(s) traced to tool results\n", len(t.Grounding.VerifiedNumbers)))
	}
	if t.StepLimitReached {
		b.WriteString("⚠ stopped at the step limit\n")
	}
	b.WriteString("\nTOOLS\n")
	if len(t.Trace) == 0 {
		b.WriteString("  (none)\n")
	}
	for _, s := range t.Trace {
		line := fmt.Sprintf("  %-26s %-16s %-14s %6dms", s.Tool, s.RequiredScope, s.Status, s.LatencyMs)
		if s.Error != "" && s.Status != "OK" {
			line += "  " + s.Error
		}
		b.WriteString(line + "\n")
	}
	meta := fmt.Sprintf("profile %s · %d step(s)", t.Profile, t.Steps)
	if t.Flow != "" {
		meta += " · flow " + t.Flow
	}
	b.WriteString(meta + "\n")
	return b.String()
}

// MarkUnverified appends "[unverified]" to each whole-number occurrence of the given numbers.
func MarkUnverified(answer string, unverified []string) string {
	if len(unverified) == 0 {
		return answer
	}
	set := map[string]bool{}
	for _, n := range unverified {
		set[n] = true
	}
	var b strings.Builder
	last := 0
	for _, loc := range numberToken.FindAllStringIndex(answer, -1) {
		start, end := loc[0], loc[1]
		token := strings.TrimRight(answer[start:end], ",")
		end = start + len(token)
		if start > 0 {
			prev, _ := utf8.DecodeLastRuneInString(answer[:start])
			if unicode.IsLetter(prev) || unicode.IsDigit(prev) || prev == '_' || prev == '.' {
				continue
			}
		}
		if !set[strings.TrimPrefix(token, "+")] {
			continue
		}
		b.WriteString(answer[last:end])
		b.WriteString("[unverified]")
		last = end
	}
	b.WriteString(answer[last:])
	return b.String()
}
