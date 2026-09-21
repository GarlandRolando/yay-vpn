package group

import (
	"context"
	"errors"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/interrupt"
	"github.com/sagernet/sing-box/common/yayauto"
	M "github.com/sagernet/sing/common/metadata"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

type mockAutoNode struct {
	outbound.Adapter
	fail   atomic.Bool
	calls  atomic.Int32
	direct bool
}

func (p *mockAutoNode) DialContext(ctx context.Context, network string, dst M.Socksaddr) (net.Conn, error) {
	p.calls.Add(1)
	if p.fail.Load() {
		return nil, errors.New("mock node failed")
	}
	if p.direct {
		return (&net.Dialer{}).DialContext(ctx, network, dst.String())
	}
	a, b := net.Pipe()
	b.Close()
	return a, nil
}
func (p *mockAutoNode) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("mock unsupported")
}
func TestYayAutoRetriesAnotherNodeWithoutReplayingStream(t *testing.T) {
	a := &mockAutoNode{Adapter: outbound.NewAdapter("mock", "a", []string{"tcp"}, nil)}
	b := &mockAutoNode{Adapter: outbound.NewAdapter("mock", "b", []string{"tcp"}, nil)}
	m := yayauto.New(context.Background(), []string{"a", "b"}, "", func(ctx context.Context, tag string) (time.Duration, error) {
		if tag == "a" {
			if a.fail.Load() {
				return 0, errors.New("dead")
			}
			return time.Millisecond, nil
		}
		return time.Second, nil
	})
	m.Start()
	defer m.Close()
	deadline := time.Now().Add(time.Second)
	for len(m.Snapshot()) < 2 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	s := &YayAuto{tags: []string{"a", "b"}, nodes: map[string]adapter.Outbound{"a": a, "b": b}, monitor: m, conns: interrupt.NewGroup()}
	a.fail.Store(true)
	conn, err := s.DialContext(context.Background(), "tcp", M.ParseSocksaddr("test.invalid:443"))
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
	if a.calls.Load() != 1 || b.calls.Load() != 1 || s.Now() != "b" {
		t.Fatal("did not retry remaining healthy route")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err = s.DialContext(ctx, "tcp", M.ParseSocksaddr("test.invalid:443")); err == nil {
		t.Fatal("cancelled request dialed")
	}
}
func TestYayProbeRejectsRedirectAndErrors(t *testing.T) {
	var status atomic.Int32
	status.Store(302)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(int(status.Load())) }))
	defer server.Close()
	node := &mockAutoNode{direct: true}
	s := &YayAuto{}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	for _, code := range []int32{302, 200, 503} {
		status.Store(code)
		if _, err := s.probe(ctx, node, server.URL); err == nil {
			t.Fatalf("accepted HTTP %d", code)
		}
	}
	status.Store(204)
	if _, err := s.probe(ctx, node, server.URL); err != nil {
		t.Fatal(err)
	}
	if node.calls.Load() != 4 {
		t.Fatal("probe bypassed configured outbound")
	}
}
