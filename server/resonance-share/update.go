package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// serverVersion is set from the release tag when the image is built
// (-ldflags "-X main.serverVersion=1.1").
var serverVersion = "1.0"

const updateCheckInterval = 24 * time.Hour

// updateChecker looks up the newest server release on GitHub once a day, so the log and /info
// can tell the owner that an update is out. It only reads public release data.
type updateChecker struct {
	apiBase string // https://api.github.com, replaceable in tests
	repo    string // owner/name
	client  *http.Client

	mu        sync.Mutex
	latest    string
	announced string
}

func newUpdateChecker(repo string) *updateChecker {
	return &updateChecker{
		apiBase: "https://api.github.com",
		repo:    repo,
		client:  &http.Client{Timeout: 20 * time.Second},
	}
}

// Latest is the newest released version, or "" before the first successful check.
func (u *updateChecker) Latest() string {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.latest
}

func (u *updateChecker) loop() {
	for {
		if err := u.check(); err != nil {
			log.Printf("update check failed: %v", err)
		}
		time.Sleep(updateCheckInterval)
	}
}

func (u *updateChecker) check() error {
	req, err := http.NewRequest(http.MethodGet, fmt.Sprintf("%s/repos/%s/releases?per_page=30", u.apiBase, u.repo), nil)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "resonance-share/"+serverVersion)
	resp, err := u.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("GitHub answered %s", resp.Status)
	}
	var releases []struct {
		TagName    string `json:"tag_name"`
		Draft      bool   `json:"draft"`
		Prerelease bool   `json:"prerelease"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&releases); err != nil {
		return err
	}
	newest := ""
	for _, r := range releases {
		// The repository also holds app releases (v3.0.0); server releases are server-1.0.
		v, ok := strings.CutPrefix(r.TagName, "server-")
		if !ok || r.Draft || r.Prerelease {
			continue
		}
		if _, ok := parseVersion(v); ok && (newest == "" || newerVersion(v, newest)) {
			newest = v
		}
	}
	if newest == "" {
		return nil
	}
	u.mu.Lock()
	u.latest = newest
	announce := newerVersion(newest, serverVersion) && u.announced != newest
	if announce {
		u.announced = newest
	}
	u.mu.Unlock()
	if announce {
		log.Printf("update available: %s -> %s, see https://github.com/%s/releases/tag/server-%s",
			serverVersion, newest, u.repo, newest)
	}
	return nil
}

// parseVersion reads "major.minor" or "major.minor.patch"; a missing patch counts as 0.
func parseVersion(v string) ([3]int, bool) {
	var out [3]int
	parts := strings.Split(v, ".")
	if len(parts) != 2 && len(parts) != 3 {
		return out, false
	}
	for i, p := range parts {
		n, err := strconv.Atoi(p)
		if err != nil || n < 0 {
			return out, false
		}
		out[i] = n
	}
	return out, true
}

// newerVersion reports whether a is a later version than b. Unreadable versions are never newer.
func newerVersion(a, b string) bool {
	va, okA := parseVersion(a)
	vb, okB := parseVersion(b)
	if !okA || !okB {
		return false
	}
	for i := range va {
		if va[i] != vb[i] {
			return va[i] > vb[i]
		}
	}
	return false
}
