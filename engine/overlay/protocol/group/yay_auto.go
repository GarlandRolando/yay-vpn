// Copyright (C) 2026 Yay VPN contributors. SPDX-License-Identifier: GPL-3.0-or-later
package group

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing-box/common/yayauto"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/ntp"
	"github.com/sagernet/sing/service"
)

var _ adapter.OutboundGroup = (*YayAuto)(nil)

type YayAuto struct {
	outbound.Adapter
	ctx         context.Context
	manager     adapter.OutboundManager
	connection  adapter.ConnectionManager
	tags, urls  []string
	nodes       map[string]adapter.Outbound
	historyPath string
	monitor     *yayauto.Monitor
	conns       *interrupt.Group
	mu          sync.Mutex
	selected    string
}

func NewYayAuto(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.YayAutoOutboundOptions) (adapter.Outbound, error) {
	if len(options.Outbounds) == 0 || len(options.Outbounds) > 64 {
		return nil, fmt.Errorf("Yay Auto requires 1–64 authorized nodes")
	}
	urls := options.URLs
	if len(urls) == 0 {
		urls = []string{"https://cp.cloudflare.com/generate_204", "https://www.gstatic.com/generate_204"}
	}
	if len(urls) > 2 {
		return nil, fmt.Errorf("too many test URLs")
	}
	return &YayAuto{Adapter: outbound.NewAdapter(C.TypeURLTest, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.Outbounds), ctx: ctx, manager: service.FromContext[adapter.OutboundManager](ctx), connection: service.FromContext[adapter.ConnectionManager](ctx), tags: options.Outbounds, urls: urls, historyPath: options.HistoryPath, conns: interrupt.NewGroup()}, nil
}
func (s *YayAuto) Start() error {
	s.nodes = map[string]adapter.Outbound{}
	for _, tag := range s.tags {
		p, ok := s.manager.Outbound(tag)
		if !ok {
			return fmt.Errorf("missing Auto node %s", tag)
		}
		s.nodes[tag] = p
	}
	s.monitor = yayauto.New(s.ctx, s.tags, s.historyPath, func(ctx context.Context, tag string) (time.Duration, error) {
		return yayauto.Hedge(ctx, func(c context.Context, url string) (time.Duration, error) { return s.probe(c, s.nodes[tag], url) }, s.urls)
	})
	return nil
}
func (s *YayAuto) PostStart() error { s.monitor.Start(); return nil }
func (s *YayAuto) Close() error {
	if s.monitor != nil {
		s.monitor.Close()
	}
	s.conns.Interrupt(true)
	return nil
}
func (s *YayAuto) All() []string     { return append([]string(nil), s.tags...) }
func (s *YayAuto) Now() string       { s.mu.Lock(); defer s.mu.Unlock(); return s.selected }
func (s *YayAuto) InterfaceUpdated() { s.CheckOutbounds() }
func (s *YayAuto) CheckOutbounds() {
	if s.monitor != nil {
		s.monitor.NetworkChanged()
	}
}
func (s *YayAuto) URLTest(ctx context.Context) (map[string]uint16, error) {
	s.monitor.Trigger()
	result := map[string]uint16{}
	for tag, row := range s.monitor.Snapshot() {
		if _, ok := s.nodes[tag]; ok {
			result[tag] = uint16(min(65535, row.Delay))
		}
	}
	return result, ctx.Err()
}
func (s *YayAuto) pick(network, exclude string) adapter.Outbound {
	var allowed []string
	for _, tag := range s.tags {
		for _, n := range s.nodes[tag].Network() {
			if n == network {
				allowed = append(allowed, tag)
				break
			}
		}
	}
	tag := s.monitor.Pick(allowed, exclude)
	s.mu.Lock()
	s.selected = tag
	s.mu.Unlock()
	return s.nodes[tag]
}
func (s *YayAuto) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	s.monitor.WaitReady(ctx)
	var last error = fmt.Errorf("no healthy Auto route")
	exclude := ""
	for i := 0; i < 2; i++ {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		p := s.pick(N.NetworkName(network), exclude)
		if p == nil {
			s.monitor.Trigger()
			break
		}
		attempt, cancel := context.WithTimeout(ctx, 5*time.Second)
		conn, err := p.DialContext(attempt, network, destination)
		cancel()
		if err == nil {
			return s.conns.NewConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
		}
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		last = err
		exclude = p.Tag()
		s.monitor.Failed(exclude)
	}
	return nil, last
}
func (s *YayAuto) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	s.monitor.WaitReady(ctx)
	var last error = fmt.Errorf("no healthy Auto UDP route")
	exclude := ""
	for i := 0; i < 2; i++ {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		p := s.pick(N.NetworkUDP, exclude)
		if p == nil {
			s.monitor.Trigger()
			break
		}
		attempt, cancel := context.WithTimeout(ctx, 5*time.Second)
		conn, err := p.ListenPacket(attempt, destination)
		cancel()
		if err == nil {
			return s.conns.NewPacketConn(conn, interrupt.IsExternalConnectionFromContext(ctx)), nil
		}
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		last = err
		exclude = p.Tag()
		s.monitor.Failed(exclude)
	}
	return nil, last
}
func (s *YayAuto) NewConnectionEx(ctx context.Context, conn net.Conn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	s.connection.NewConnection(interrupt.ContextWithIsExternalConnection(ctx), s, conn, metadata, onClose)
}
func (s *YayAuto) NewPacketConnectionEx(ctx context.Context, conn N.PacketConn, metadata adapter.InboundContext, onClose N.CloseHandlerFunc) {
	s.connection.NewPacketConnection(interrupt.ContextWithIsExternalConnection(ctx), s, conn, metadata, onClose)
}

// Measure an HTTPS response through the real outbound, including its handshake.
// A redirect/captive portal cannot be counted as working VPN access.
func (s *YayAuto) probe(ctx context.Context, p adapter.Outbound, url string) (time.Duration, error) {
	tr := &http.Transport{Proxy: nil, DisableKeepAlives: true, DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
		return p.DialContext(ctx, network, M.ParseSocksaddr(addr))
	}, TLSClientConfig: &tls.Config{Time: ntp.TimeFuncFromContext(ctx), RootCAs: adapter.RootPoolFromContext(ctx)}}
	defer tr.CloseIdleConnections()
	client := &http.Client{Transport: tr, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return 0, err
	}
	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	resp.Body.Close()
	if resp.StatusCode != 204 {
		return 0, fmt.Errorf("test endpoint returned %d", resp.StatusCode)
	}
	return time.Since(start), nil
}
