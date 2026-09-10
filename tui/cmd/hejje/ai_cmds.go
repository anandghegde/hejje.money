package main

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"strings"

	"github.com/spf13/cobra"

	"hejje.money/tui/internal/ui"
)

func aiCmd() *cobra.Command {
	var flow string
	cmd := &cobra.Command{Use: "ai [question]", Short: "Ask Hejje AI: one-shot with a question, interactive without",
		RunE: func(_ *cobra.Command, args []string) error {
			if len(args) == 0 {
				return aiInteractive(os.Stdin, os.Stdout)
			}
			t, err := client.Ask(strings.Join(args, " "), "", flow)
			if err != nil {
				return err
			}
			emit(t, func() { fmt.Print(ui.RenderAiTurn(t)) })
			return nil
		}}
	cmd.Flags().StringVar(&flow, "flow", "", "canned analyst flow: why_ranked_first, compare, working_today")
	return cmd
}

// aiInteractive is a line-based conversation: each answer continues the same conversation until /new.
func aiInteractive(in io.Reader, out io.Writer) error {
	if status, err := client.AiStatus(); err == nil && !status.Enabled {
		fmt.Fprintf(out, "Hejje AI is off: %s\n", status.Reason)
		return nil
	}
	fmt.Fprintln(out, "Hejje AI — ask a question. /new starts a new conversation, /quit exits.")
	scanner := bufio.NewScanner(in)
	conversation := ""
	for {
		fmt.Fprint(out, "ai> ")
		if !scanner.Scan() {
			fmt.Fprintln(out)
			return scanner.Err()
		}
		line := strings.TrimSpace(scanner.Text())
		switch line {
		case "":
			continue
		case "/quit", "/exit":
			return nil
		case "/new":
			conversation = ""
			fmt.Fprintln(out, "(new conversation)")
			continue
		}
		t, err := client.Ask(line, conversation, "")
		if err != nil {
			fmt.Fprintln(out, "error:", err)
			continue
		}
		conversation = t.ConversationID
		fmt.Fprint(out, ui.RenderAiTurn(t))
		fmt.Fprintln(out)
	}
}
