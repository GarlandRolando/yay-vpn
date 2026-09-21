// SPDX-License-Identifier: GPL-3.0-or-later
package option

// YayAuto is opt-in. Ordinary URLTest/manual outbounds remain unchanged.
type YayAutoOutboundOptions struct {
	Outbounds   []string `json:"outbounds"`
	URLs        []string `json:"test_urls"`
	HistoryPath string   `json:"history_path,omitempty"`
}
