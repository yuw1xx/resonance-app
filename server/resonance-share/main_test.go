package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

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

func TestClientIP(t *testing.T) {
	cases := []struct {
		trust, remote, cf, xff, want string
	}{
		// Direct: forwarded headers are ignored (they could be faked) and the port is dropped.
		{"none", "203.0.113.5:51234", "1.1.1.1", "2.2.2.2", "203.0.113.5"},
		{"none", "[2001:db8::1]:443", "", "", "2001:db8::1"},
		{"cloudflare", "172.18.0.3:40000", "198.51.100.7", "", "198.51.100.7"},
		{"cloudflare", "172.18.0.3:40000", "", "", "172.18.0.3"},
		// Behind a proxy the last entry is the one the proxy added; earlier ones are client input.
		{"proxy", "127.0.0.1:9000", "", "6.6.6.6, 198.51.100.9", "198.51.100.9"},
		{"proxy", "127.0.0.1:9000", "", "", "127.0.0.1"},
	}
	for _, c := range cases {
		s := &server{trustProxy: c.trust}
		r := httptest.NewRequest("GET", "/info", nil)
		r.RemoteAddr = c.remote
		if c.cf != "" {
			r.Header.Set("CF-Connecting-IP", c.cf)
		}
		if c.xff != "" {
			r.Header.Set("X-Forwarded-For", c.xff)
		}
		if got := s.clientIP(r); got != c.want {
			t.Errorf("%s %s cf=%q xff=%q: got %q want %q", c.trust, c.remote, c.cf, c.xff, got, c.want)
		}
	}
}

func TestNewerVersion(t *testing.T) {
	cases := []struct {
		a, b string
		want bool
	}{
		{"1.1", "1.0", true},
		{"1.0.1", "1.0", true},
		{"1.0.10", "1.0.9", true},
		{"2.0", "1.9.9", true},
		{"1.0", "1.0.0", false},
		{"0.9", "1.0", false},
		{"1", "1.0", false},
		{"dev", "1.0", false},
	}
	for _, c := range cases {
		if got := newerVersion(c.a, c.b); got != c.want {
			t.Errorf("newerVersion(%q, %q) = %v, want %v", c.a, c.b, got, c.want)
		}
	}
}

func TestUpdateCheckReadsOnlyServerReleases(t *testing.T) {
	gh := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`[
			{"tag_name": "v9.0.0"},
			{"tag_name": "server-1.3", "prerelease": true},
			{"tag_name": "server-1.2", "draft": true},
			{"tag_name": "server-1.1"},
			{"tag_name": "server-1.0"}
		]`))
	}))
	defer gh.Close()
	u := newUpdateChecker("owner/repo")
	u.apiBase = gh.URL
	if err := u.check(); err != nil {
		t.Fatal(err)
	}
	if got := u.Latest(); got != "1.1" {
		t.Errorf("latest = %q, want 1.1 (app tags, drafts and pre-releases are skipped)", got)
	}
}
