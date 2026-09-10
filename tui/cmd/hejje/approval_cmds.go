package main

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	"github.com/spf13/cobra"

	"hejje.money/tui/internal/ui"
)

func approvalsCmd() *cobra.Command {
	var status string
	cmd := &cobra.Command{Use: "approvals", Short: "Agent proposals awaiting (or past) a human decision", RunE: func(_ *cobra.Command, _ []string) error {
		list, err := client.Approvals(status)
		if err != nil {
			return err
		}
		emit(list, func() { fmt.Print(ui.RenderApprovalsTable(list, time.Now())) })
		return nil
	}}
	cmd.Flags().StringVar(&status, "status", "PENDING", "PENDING, APPROVED, REJECTED, EXPIRED, FAILED or ALL")
	return cmd
}

func approveCmd() *cobra.Command {
	var yes bool
	cmd := &cobra.Command{Use: "approve <id>", Short: "Approve an agent proposal (policy and risk are re-checked, then the order is sent)", Args: cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			a, err := client.Approval(args[0])
			if err != nil {
				return err
			}
			if a.Status != "PENDING" {
				return fmt.Errorf("approval %s is %s", a.ID, a.Status)
			}
			if !jsonOut {
				fmt.Print(ui.RenderApproval(a, time.Now()))
			}
			if !yes && !askYesNo(os.Stdin, os.Stdout, "Approve and send? [y/N] ") {
				fmt.Println("not approved")
				return nil
			}
			done, err := client.Approve(a.ID)
			if err != nil {
				return err
			}
			emit(done, func() { fmt.Print(ui.RenderApproval(done, time.Now())) })
			return nil
		}}
	cmd.Flags().BoolVarP(&yes, "yes", "y", false, "skip the confirmation prompt")
	return cmd
}

func rejectCmd() *cobra.Command {
	var reason string
	cmd := &cobra.Command{Use: "reject <id>", Short: "Reject an agent proposal", Args: cobra.ExactArgs(1), RunE: func(_ *cobra.Command, args []string) error {
		done, err := client.Reject(args[0], reason)
		if err != nil {
			return err
		}
		emit(done, func() { fmt.Print(ui.RenderApproval(done, time.Now())) })
		return nil
	}}
	cmd.Flags().StringVar(&reason, "reason", "", "why (shown in the audit trail)")
	return cmd
}

func askYesNo(in io.Reader, out io.Writer, prompt string) bool {
	fmt.Fprint(out, prompt)
	line, _ := bufio.NewReader(in).ReadString('\n')
	answer := strings.ToLower(strings.TrimSpace(line))
	return answer == "y" || answer == "yes"
}
