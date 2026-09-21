// Copyright (C) 2026 Yay VPN contributors. SPDX-License-Identifier: GPL-3.0-or-later
// Independent monitoring implementation; does not contain Hiddify source.
package yayauto

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

type Sample struct {
	Delay int64     `json:"delay"`
	At    time.Time `json:"at"`
}
type entry struct {
	Sample
	fresh   bool
	blocked time.Time
	epoch   uint64
}
type Probe func(context.Context, string) (time.Duration, error)
type Monitor struct {
	mu                sync.Mutex
	tags              []string
	entries           map[string]entry
	probe             Probe
	path              string
	ctx               context.Context
	cancel            context.CancelFunc
	wake              chan struct{}
	ready             chan struct{}
	done              chan struct{}
	readyOnce         sync.Once
	started           bool
	priority          string
	cursor            uint64
	interval, timeout time.Duration
}

func New(parent context.Context, tags []string, path string, probe Probe) *Monitor {
	ctx, cancel := context.WithCancel(parent)
	m := &Monitor{tags: append([]string(nil), tags...), entries: map[string]entry{}, probe: probe, path: path, ctx: ctx, cancel: cancel, wake: make(chan struct{}, 1), ready: make(chan struct{}), done: make(chan struct{}), interval: 30 * time.Second, timeout: 5 * time.Second}
	m.load()
	return m
}
func (m *Monitor) Start() {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.started {
		return
	}
	m.started = true
	go m.loop()
}
func (m *Monitor) Close() {
	m.cancel()
	m.mu.Lock()
	started := m.started
	m.mu.Unlock()
	if started {
		<-m.done
		m.save()
	}
}
func (m *Monitor) Trigger() {
	select {
	case m.wake <- struct{}{}:
	default:
	}
}

// Old samples remain a startup hint; fresh tests always outrank them.
func (m *Monitor) NetworkChanged() {
	m.mu.Lock()
	for tag, e := range m.entries {
		e.fresh = false
		e.blocked = time.Time{}
		e.epoch++
		m.entries[tag] = e
	}
	m.mu.Unlock()
	m.Trigger()
}
func (m *Monitor) Failed(tag string) {
	m.mu.Lock()
	e := m.entries[tag]
	m.priority = tag
	e.fresh = false
	e.blocked = time.Now().Add(5 * time.Second)
	e.epoch++
	e.Sample = Sample{}
	m.entries[tag] = e
	m.mu.Unlock()
	m.Trigger()
}
func (m *Monitor) WaitReady(ctx context.Context) {
	if m.hasResult() {
		return
	}
	timer := time.NewTimer(time.Second)
	defer timer.Stop()
	select {
	case <-m.ready:
	case <-ctx.Done():
	case <-m.ctx.Done():
	case <-timer.C:
	}
}
func (m *Monitor) hasResult() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, t := range m.tags {
		e := m.entries[t]
		if e.Delay > 0 && time.Since(e.At) < 30*time.Minute {
			return true
		}
	}
	return false
}

// Rotate new connections among close performers. TCP streams are never split or replayed.
func (m *Monitor) Pick(allowed []string, exclude string) string {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := time.Now()
	type candidate struct {
		tag   string
		delay int64
	}
	var good, unknown []candidate
	for _, tag := range allowed {
		if tag == exclude {
			continue
		}
		e := m.entries[tag]
		if now.Before(e.blocked) {
			continue
		}
		if e.Delay > 0 && now.Sub(e.At) < 30*time.Minute {
			delay := e.Delay
			if !e.fresh {
				delay += 20000
			}
			good = append(good, candidate{tag, delay})
		} else {
			unknown = append(unknown, candidate{tag, 0})
		}
	}
	if len(good) == 0 {
		good = unknown
	}
	if len(good) == 0 {
		return ""
	}
	sort.SliceStable(good, func(i, j int) bool { return good[i].delay < good[j].delay })
	n := 1
	for n < len(good) && good[n].delay <= good[0].delay+100 {
		n++
	}
	selected := good[m.cursor%uint64(n)].tag
	m.cursor++
	return selected
}
func (m *Monitor) Snapshot() map[string]Sample {
	m.mu.Lock()
	defer m.mu.Unlock()
	r := map[string]Sample{}
	for tag, e := range m.entries {
		if e.Delay > 0 && time.Since(e.At) < 30*time.Minute {
			r[tag] = e.Sample
		}
	}
	return r
}
func (m *Monitor) loop() {
	defer close(m.done)
	m.cycle()
	ticker := time.NewTicker(m.interval)
	defer ticker.Stop()
	for {
		select {
		case <-m.ctx.Done():
			return
		case <-ticker.C:
		case <-m.wake:
			// Coalesce connection errors, avoiding unlimited test goroutines.
			timer := time.NewTimer(250 * time.Millisecond)
			select {
			case <-timer.C:
			case <-m.ctx.Done():
				timer.Stop()
				return
			}
		}
		m.cycle()
	}
}
func (m *Monitor) cycle() {
	jobs := make(chan string)
	var workers sync.WaitGroup
	for i := 0; i < 10; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for tag := range jobs {
				if m.ctx.Err() != nil {
					return
				}
				m.mu.Lock()
				epoch := m.entries[tag].epoch
				m.mu.Unlock()
				ctx, cancel := context.WithTimeout(m.ctx, m.timeout)
				delay, err := m.probe(ctx, tag)
				cancel()
				if m.ctx.Err() != nil {
					return
				}
				m.mu.Lock()
				e := m.entries[tag]
				if e.epoch == epoch {
					if err == nil {
						e.Sample = Sample{max(1, delay.Milliseconds()), time.Now()}
						e.fresh = true
						e.blocked = time.Time{}
						m.readyOnce.Do(func() { close(m.ready) })
					} else {
						e.Sample = Sample{}
						e.fresh = false
						e.blocked = time.Now().Add(5 * time.Second)
					}
					m.entries[tag] = e
				}
				m.mu.Unlock()
			}
		}()
	}
	m.mu.Lock()
	tags := append([]string(nil), m.tags...)
	if m.priority != "" {
		for i, tag := range tags {
			if tag == m.priority {
				tags[0], tags[i] = tags[i], tags[0]
				break
			}
		}
		m.priority = ""
	}
	m.mu.Unlock()
	for _, tag := range tags {
		select {
		case jobs <- tag:
		case <-m.ctx.Done():
			close(jobs)
			workers.Wait()
			return
		}
	}
	close(jobs)
	workers.Wait()
	m.save()
}
func (m *Monitor) load() {
	if m.path == "" {
		return
	}
	file, err := os.Open(m.path)
	if err != nil {
		return
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || info.Size() > 128*1024 {
		return
	}
	var rows map[string]Sample
	if json.NewDecoder(file).Decode(&rows) != nil || len(rows) > 512 {
		return
	}
	for tag, s := range rows {
		if s.Delay > 0 && s.Delay <= 60000 && time.Since(s.At) >= 0 && time.Since(s.At) < 30*time.Minute {
			m.entries[tag] = entry{Sample: s}
		}
	}
}
func (m *Monitor) save() {
	if m.path == "" {
		return
	}
	rows := m.Snapshot()
	if len(rows) > 512 {
		return
	}
	data, err := json.Marshal(rows)
	if err != nil {
		return
	}
	if os.MkdirAll(filepath.Dir(m.path), 0700) != nil {
		return
	}
	f, err := os.CreateTemp(filepath.Dir(m.path), ".yay-latency-")
	if err != nil {
		return
	}
	name := f.Name()
	defer os.Remove(name)
	if f.Chmod(0600) != nil {
		f.Close()
		return
	}
	_, err = f.Write(data)
	closeErr := f.Close()
	if err == nil && closeErr == nil {
		_ = os.Rename(name, m.path)
	}
}

// Hedge the independent test URL after a short delay. A blocked test website does
// not force every node to wait for the full primary timeout. First valid 204 wins.
func Hedge(ctx context.Context, probe func(context.Context, string) (time.Duration, error), urls []string) (time.Duration, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	type result struct {
		d time.Duration
		e error
	}
	results := make(chan result, len(urls))
	for i, url := range urls {
		go func(i int, url string) {
			if i > 0 {
				timer := time.NewTimer(500 * time.Millisecond)
				defer timer.Stop()
				select {
				case <-ctx.Done():
					results <- result{e: ctx.Err()}
					return
				case <-timer.C:
				}
			}
			d, e := probe(ctx, url)
			results <- result{d, e}
		}(i, url)
	}
	var last error = errors.New("no test URL")
	for range urls {
		select {
		case <-ctx.Done():
			return 0, ctx.Err()
		case r := <-results:
			if r.e == nil && ctx.Err() == nil {
				return r.d, nil
			}
			last = r.e
		}
	}
	return 0, last
}
