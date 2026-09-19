package ui

import "strings"

// Braille renders values as a line chart of width×height characters, each character a 2×4 grid of braille dots. The
// series is resampled to the dot columns and consecutive points are joined vertically so the line has no gaps.
func Braille(values []float64, width, height int) []string {
	if width <= 0 || height <= 0 {
		return nil
	}
	cols, rows := width*2, height*4
	grid := make([][]bool, rows)
	for i := range grid {
		grid[i] = make([]bool, cols)
	}
	if len(values) > 0 {
		lo, hi := values[0], values[0]
		for _, v := range values {
			lo, hi = min(lo, v), max(hi, v)
		}
		y := func(v float64) int {
			if hi == lo {
				return rows / 2
			}
			return int((hi - v) / (hi - lo) * float64(rows-1))
		}
		prev := -1
		for x := 0; x < cols; x++ {
			i := 0
			if len(values) > 1 && cols > 1 {
				i = x * (len(values) - 1) / (cols - 1)
			}
			cur := y(values[i])
			from, to := cur, cur
			if prev >= 0 {
				from, to = min(prev, cur), max(prev, cur)
			}
			for r := from; r <= to; r++ {
				grid[r][x] = true
			}
			prev = cur
		}
	}
	// dot bits of a braille cell: column 0 rows 0-3 = 0x01 0x02 0x04 0x40, column 1 = 0x08 0x10 0x20 0x80
	bits := [4][2]rune{{0x01, 0x08}, {0x02, 0x10}, {0x04, 0x20}, {0x40, 0x80}}
	out := make([]string, height)
	for cy := 0; cy < height; cy++ {
		var b strings.Builder
		for cx := 0; cx < width; cx++ {
			var r rune = 0x2800
			for dy := 0; dy < 4; dy++ {
				for dx := 0; dx < 2; dx++ {
					if grid[cy*4+dy][cx*2+dx] {
						r |= bits[dy][dx]
					}
				}
			}
			b.WriteRune(r)
		}
		out[cy] = b.String()
	}
	return out
}
