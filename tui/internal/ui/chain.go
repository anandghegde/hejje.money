package ui

import (
	"fmt"
	"strings"

	"hejje.money/tui/internal/api"
)

// RenderChain prints an option chain: calls on the left, puts on the right, the ATM strike marked.
func RenderChain(c api.OptionChain) string {
	var b strings.Builder
	b.WriteString(fmt.Sprintf("%s %s  forward %s (%s)  ATM %s  PCR(OI) %s  max pain %s\n", c.Underlying, c.Expiry, fixed(c.Forward, 2), c.ForwardSource,
		fixed(c.ATMStrike, 0), fixed(c.PCROI, 2), fixed(c.MaxPain, 0)))
	for _, n := range c.Notes {
		b.WriteString("  ! " + n + "\n")
	}
	b.WriteString(fmt.Sprintf("%10s %7s %6s %9s   %-9s   %-9s %6s %7s %10s\n", "CE OI", "IV", "DELTA", "LTP", "STRIKE", "LTP", "DELTA", "IV", "PE OI"))
	for _, r := range c.Rows {
		mark := " "
		if c.ATMStrike != nil && *c.ATMStrike == r.Strike {
			mark = "*"
		}
		b.WriteString(fmt.Sprintf("%s %s  %-9s  %s\n", side(r.Call, true), mark, fmt.Sprintf("%.0f", r.Strike), side(r.Put, false)))
	}
	return b.String()
}

func side(q *api.OptionQuote, call bool) string {
	if q == nil {
		return strings.Repeat(" ", 35)
	}
	iv := "—"
	if q.IV != nil {
		iv = fmt.Sprintf("%.1f%%", *q.IV*100)
	}
	if call {
		return fmt.Sprintf("%10d %7s %6s %9s", q.OI, iv, fixed(q.Delta, 2), fixed(q.Last, 2))
	}
	return fmt.Sprintf("%-9s %6s %7s %10d", fixed(q.Last, 2), fixed(q.Delta, 2), iv, q.OI)
}

func fixed(v *float64, digits int) string {
	if v == nil {
		return "—"
	}
	return fmt.Sprintf("%.*f", digits, *v)
}
