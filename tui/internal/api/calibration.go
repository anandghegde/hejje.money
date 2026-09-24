package api

import (
	"net/http"
	"net/url"
)

// CalibrationBucket is one probability bucket; Rate and the Wilson bounds are nil under the minimum count.
type CalibrationBucket struct {
	Lo              float64  `json:"lo"`
	Hi              float64  `json:"hi"`
	N               int      `json:"n"`
	Hits            int      `json:"hits"`
	MeanProbability *float64 `json:"meanProbability"`
	Rate            *float64 `json:"rate"`
	WilsonLo        *float64 `json:"wilsonLo"`
	WilsonHi        *float64 `json:"wilsonHi"`
}

type CalibrationTopBottom struct {
	Top       CalibrationBucket `json:"top"`
	Bottom    CalibrationBucket `json:"bottom"`
	Separated bool              `json:"separated"`
}

// CalibrationReport is GET /calibration (plan M9.2).
type CalibrationReport struct {
	Purpose     string                `json:"purpose"`
	Version     *string               `json:"version"`
	N           int                   `json:"n"`
	None        int                   `json:"none"`
	Pending     int                   `json:"pending"`
	Sessions    int                   `json:"sessions"`
	Buckets     []CalibrationBucket   `json:"buckets"`
	Brier       *float64              `json:"brier"`
	Ece         *float64              `json:"ece"`
	TopVsBottom *CalibrationTopBottom `json:"topVsBottom"`
	Passes      bool                  `json:"passes"`
	Reasons     []string              `json:"reasons"`
}

type CalibrationPurpose struct {
	Purpose     string  `json:"purpose"`
	Version     string  `json:"version"`
	Predictions int     `json:"predictions"`
	Labelled    int     `json:"labelled"`
	First       *string `json:"first"`
	Last        *string `json:"last"`
}

func (c *Client) Calibration(purpose, version, bot, from, to string) (CalibrationReport, error) {
	var r CalibrationReport
	q := url.Values{}
	for k, v := range map[string]string{"purpose": purpose, "version": version, "bot": bot, "from": from, "to": to} {
		if v != "" {
			q.Set(k, v)
		}
	}
	return r, c.do(http.MethodGet, "/calibration?"+q.Encode(), nil, false, &r)
}

func (c *Client) CalibrationPurposes() ([]CalibrationPurpose, error) {
	var out []CalibrationPurpose
	return out, c.do(http.MethodGet, "/calibration/purposes", nil, false, &out)
}
