package ui

import "fmt"

// Rupees formats paise as a signed rupee string.
func Rupees(paise int64) string {
	return fmt.Sprintf("%.2f", float64(paise)/100.0)
}
