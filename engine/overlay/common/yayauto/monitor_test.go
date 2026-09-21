package yayauto

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sync/atomic"
	"testing"
	"time"
)

func eventually(t *testing.T, check func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if check() {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("condition not reached")
}
func TestIncrementalBeforeSlowBatch(t *testing.T) {
	slow := make(chan struct{})
	m := New(context.Background(), []string{"slow", "fast"}, "", func(ctx context.Context, tag string) (time.Duration, error) {
		if tag == "slow" {
			select {
			case <-slow:
			case <-ctx.Done():
				return 0, ctx.Err()
			}
		}
		return 20 * time.Millisecond, nil
	})
	m.Start()
	defer m.Close()
	eventually(t, func() bool { return m.Pick([]string{"slow", "fast"}, "") == "fast" })
	select {
	case <-m.done:
		t.Fatal("monitor stopped")
	default:
	}
	close(slow)
}
func TestConcurrencyBoundAndCancellation(t *testing.T) {
	var active, peak atomic.Int32
	tags := []string{}
	for i := 0; i < 25; i++ {
		tags = append(tags, fmt.Sprint(i))
	}
	m := New(context.Background(), tags, "", func(ctx context.Context, _ string) (time.Duration, error) {
		n := active.Add(1)
		defer active.Add(-1)
		for {
			p := peak.Load()
			if n <= p || peak.CompareAndSwap(p, n) {
				break
			}
		}
		<-ctx.Done()
		return 0, ctx.Err()
	})
	m.Start()
	eventually(t, func() bool { return active.Load() == 10 })
	m.Close()
	if peak.Load() > 10 || active.Load() != 0 {
		t.Fatal("unbounded or leaked tests")
	}
}
func TestCacheFreshPriorityInvalidationAndExpiry(t *testing.T) {
	path := filepath.Join(t.TempDir(), "history.json")
	m := New(context.Background(), []string{"cached", "fresh"}, path, nil)
	m.entries["cached"] = entry{Sample: Sample{1, time.Now()}}
	m.save()
	m = New(context.Background(), []string{"cached", "fresh"}, path, nil)
	if m.Pick([]string{"cached", "fresh"}, "") != "cached" {
		t.Fatal("cache not reused")
	}
	m.entries["fresh"] = entry{Sample: Sample{100, time.Now()}, fresh: true}
	if m.Pick([]string{"cached", "fresh"}, "") != "fresh" {
		t.Fatal("stale cache beat fresh route")
	}
	m.Failed("fresh")
	if m.Pick([]string{"cached", "fresh"}, "") != "cached" {
		t.Fatal("failed node retained")
	}
	m.save()
	loaded := New(context.Background(), []string{"cached", "fresh"}, path, nil)
	if loaded.entries["fresh"].Delay != 0 {
		t.Fatal("invalid result persisted")
	}
	m.entries["cached"] = entry{Sample: Sample{1, time.Now().Add(-time.Hour)}}
	m.save()
	loaded = New(context.Background(), []string{"cached"}, path, nil)
	if loaded.hasResult() {
		t.Fatal("expired cache reused")
	}
	if info, err := os.Stat(path); err != nil || (runtime.GOOS != "windows" && info.Mode().Perm()&0077 != 0) {
		t.Fatal("history is not private")
	}
}
func TestFailureCannotBeOverwrittenByOldProbe(t *testing.T) {
	entered := make(chan struct{})
	finish := make(chan struct{})
	m := New(context.Background(), []string{"a"}, "", func(ctx context.Context, _ string) (time.Duration, error) {
		close(entered)
		<-finish
		return time.Millisecond, nil
	})
	done := make(chan struct{})
	go func() { m.cycle(); close(done) }()
	<-entered
	m.Failed("a")
	close(finish)
	<-done
	if m.Pick([]string{"a"}, "") != "" {
		t.Fatal("old in-flight success resurrected failed route")
	}
}
func TestHedgeUsesFallbackWithoutWaitingPrimary(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	var cancelled atomic.Bool
	_, err := Hedge(ctx, func(ctx context.Context, url string) (time.Duration, error) {
		if url == "blocked" {
			<-ctx.Done()
			cancelled.Store(true)
			return 0, ctx.Err()
		}
		return time.Millisecond, nil
	}, []string{"blocked", "working"})
	if err != nil {
		t.Fatal(err)
	}
	eventually(t, cancelled.Load)
}
func TestAllDeadAndCancelledNeverSucceed(t *testing.T) {
	m := New(context.Background(), []string{"a", "b"}, "", func(context.Context, string) (time.Duration, error) { return 0, errors.New("dead") })
	m.cycle()
	if m.Pick([]string{"a", "b"}, "") != "" {
		t.Fatal("dead pool has a winner")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := Hedge(ctx, func(context.Context, string) (time.Duration, error) { return time.Millisecond, nil }, []string{"a"})
	if err == nil {
		t.Fatal("cancelled test succeeded")
	}
}
func TestRotationExclusionAndNetworkReset(t *testing.T) {
	m := New(context.Background(), []string{"a", "b", "slow"}, "", nil)
	for tag, d := range map[string]int64{"a": 20, "b": 60, "slow": 500} {
		m.entries[tag] = entry{Sample: Sample{d, time.Now()}, fresh: true}
	}
	seen := map[string]bool{}
	for i := 0; i < 8; i++ {
		seen[m.Pick(m.tags, "")] = true
	}
	if !seen["a"] || !seen["b"] || seen["slow"] {
		t.Fatal(seen)
	}
	if m.Pick(m.tags, "a") != "b" {
		t.Fatal("retry chose same route")
	}
	m.Failed("a")
	m.NetworkChanged()
	if m.entries["b"].fresh || !m.entries["a"].blocked.IsZero() {
		t.Fatal("network change did not invalidate measurements")
	}
}
