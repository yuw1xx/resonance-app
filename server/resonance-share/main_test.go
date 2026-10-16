package main

import "testing"

func TestRangeStart(t *testing.T) {
	cases := []struct {
		header     string
		start      int64
		reachesEnd bool
	}{
		{"", 0, true},
		{"bytes=0-", 0, true},
		{"bytes=500-", 500, true},
		{"bytes=0-99", 0, false},
		{"bytes=900-999", 900, true},
		{"bytes=-100", 0, false},
		{"bytes=0-1,5-9", 0, false},
		{"bytes=5000-", 0, false},
	}
	for _, c := range cases {
		start, end := rangeStart(c.header, 1000)
		if start != c.start || end != c.reachesEnd {
			t.Errorf("%q: got (%d,%v) want (%d,%v)", c.header, start, end, c.start, c.reachesEnd)
		}
	}
}

func TestLimiterCountsBytesNotFiles(t *testing.T) {
	s := &server{rateLimitRequests: 300, rateLimitBytes: 100, ipLimiters: map[string]*ipLimiter{}}
	// 40 small files (a big multi-song share) must fit; the old per-file limit was 20.
	for i := 0; i < 40; i++ {
		if !s.allowRequest("1.2.3.4", 2) {
			t.Fatalf("request %d rejected", i)
		}
	}
	if s.allowRequest("1.2.3.4", 50) {
		t.Fatal("byte budget not enforced")
	}
	if !s.allowRequest("5.6.7.8", 50) {
		t.Fatal("limits must be per client")
	}
}
