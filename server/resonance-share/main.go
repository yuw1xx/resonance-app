// resonance-share is a small self-hosted relay for Resonance's song sharing over the internet.
// Uploads need the upload token, downloads need the file's own token, and files expire after a
// TTL. Files live on disk under DATA_DIR; there is no database.
package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	defaultMaxUploadBytes = 500 << 20 // 500 MB
	defaultTTL            = 24 * time.Hour
	defaultMaxTTLHours    = 72
	sweepInterval         = 10 * time.Minute
	rateLimitWindow       = time.Hour

	// Rate limits count bytes, since one share of many songs is many requests. The request cap
	// only stops floods of tiny requests.
	defaultRateLimitBytesPerHour    = 3 << 30 // 3 GB
	defaultRateLimitRequestsPerHour = 300
	defaultMaxStorageBytes          = 20 << 30 // 20 GB on disk across all pending shares
	partSuffix                      = ".part"
	staleParts                      = time.Hour

	// mode=remote-multi song-list links: a manifest is a small JSON blob mapping to a set
	// of already-uploaded /upload file tokens, so multiple songs can share one link.
	manifestSubdir       = "manifests"
	maxManifestSongs     = 50
	maxManifestBodyBytes = 64 << 10 // 64 KB
)

func main() {
	dataDir := envOr("DATA_DIR", "/data")
	uploadToken := os.Getenv("UPLOAD_TOKEN")
	if uploadToken == "" {
		log.Fatal("UPLOAD_TOKEN must be set")
	}
	addr := envOr("LISTEN_ADDR", ":8080")
	trustProxy := envOr("TRUST_PROXY", "none")
	switch trustProxy {
	case "none", "cloudflare", "proxy":
	default:
		log.Fatalf("TRUST_PROXY must be none, cloudflare or proxy, not %q", trustProxy)
	}
	ttl := defaultTTL
	if v := os.Getenv("TTL_HOURS"); v != "" {
		if h, err := strconv.Atoi(v); err == nil && h > 0 {
			ttl = time.Duration(h) * time.Hour
		}
	}
	maxUpload := int64(defaultMaxUploadBytes)
	if v := os.Getenv("MAX_UPLOAD_MB"); v != "" {
		if mb, err := strconv.ParseInt(v, 10, 64); err == nil && mb > 0 {
			maxUpload = mb << 20
		}
	}
	rateLimitRequests := defaultRateLimitRequestsPerHour
	if v := os.Getenv("RATE_LIMIT_REQUESTS_PER_HOUR"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			rateLimitRequests = n
		}
	}
	rateLimitBytes := int64(defaultRateLimitBytesPerHour)
	if v := os.Getenv("RATE_LIMIT_MB_PER_HOUR"); v != "" {
		if mb, err := strconv.ParseInt(v, 10, 64); err == nil && mb > 0 {
			rateLimitBytes = mb << 20
		}
	}
	maxStorage := int64(defaultMaxStorageBytes)
	if v := os.Getenv("MAX_STORAGE_GB"); v != "" {
		if gb, err := strconv.ParseInt(v, 10, 64); err == nil && gb > 0 {
			maxStorage = gb << 30
		}
	}
	if os.Getenv("RATE_LIMIT_PER_HOUR") != "" {
		log.Printf("note: RATE_LIMIT_PER_HOUR (per-file) is no longer used; see RATE_LIMIT_MB_PER_HOUR / RATE_LIMIT_REQUESTS_PER_HOUR")
	}
	maxTTLHours := defaultMaxTTLHours
	if v := os.Getenv("MAX_TTL_HOURS"); v != "" {
		if h, err := strconv.Atoi(v); err == nil && h > 0 {
			maxTTLHours = h
		}
	}

	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		log.Fatalf("cannot create data dir: %v", err)
	}
	manifestDir := filepath.Join(dataDir, manifestSubdir)
	if err := os.MkdirAll(manifestDir, 0o700); err != nil {
		log.Fatalf("cannot create manifest dir: %v", err)
	}

	s := &server{
		dataDir:           dataDir,
		manifestDir:       manifestDir,
		uploadToken:       uploadToken,
		maxUpload:         maxUpload,
		rateLimitRequests: rateLimitRequests,
		rateLimitBytes:    rateLimitBytes,
		maxStorage:        maxStorage,
		maxTTLHours:       maxTTLHours,
		trustProxy:        trustProxy,
		inflight:          make(map[string]struct{}),
		ipLimiters:        make(map[string]*ipLimiter),
	}

	go s.sweepLoop(ttl)
	if envOr("UPDATE_CHECK", "true") != "false" {
		s.updates = newUpdateChecker(envOr("UPDATE_REPO", "yuw1xx/resonance-app"))
		go s.updates.loop()
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/upload", s.handleUpload)
	mux.HandleFunc("/f/", s.handleDownload)
	mux.HandleFunc("/manifest", s.handleManifestCreate)
	mux.HandleFunc("/manifest/", s.handleManifestGet)
	mux.HandleFunc("/info", s.handleInfo)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})

	log.Printf("resonance-share %s listening on %s (data=%s defaultTTL=%s maxTTL=%dh maxUpload=%dMB rateLimit=%dMB+%dreq/hr maxStorage=%dGB trustProxy=%s)",
		serverVersion, addr, dataDir, ttl, maxTTLHours, maxUpload>>20, rateLimitBytes>>20, rateLimitRequests, maxStorage>>30, trustProxy)
	log.Fatal(http.ListenAndServe(addr, mux))
}

type ipLimiter struct {
	windowStart time.Time
	requests    int
	bytes       int64
}

type server struct {
	dataDir           string
	manifestDir       string
	uploadToken       string
	maxUpload         int64
	rateLimitRequests int
	rateLimitBytes    int64
	maxStorage        int64
	maxTTLHours       int
	trustProxy        string
	updates           *updateChecker // nil when UPDATE_CHECK=false

	mu       sync.Mutex
	inflight map[string]struct{} // tokens currently being downloaded; skip sweep/delete-on-read race

	ipMu       sync.Mutex
	ipLimiters map[string]*ipLimiter
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

// setCORS lets browser clients call the API. A wildcard is safe because auth uses an explicit
// Authorization header, never cookies, and downloads are protected by their tokens.
func setCORS(w http.ResponseWriter) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
	w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
	w.Header().Set("Access-Control-Max-Age", "86400")
}

func (s *server) handleUpload(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !s.authorized(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	ip := s.clientIP(r)
	declared := r.ContentLength // -1 when unknown (chunked)
	if !s.allowRequest(ip, max(declared, 0)) {
		w.Header().Set("Retry-After", "3600")
		http.Error(w, "rate limit exceeded, try again later", http.StatusTooManyRequests)
		return
	}
	if declared > s.maxUpload {
		http.Error(w, "file too large", http.StatusRequestEntityTooLarge)
		return
	}
	if s.storageUsed()+max(declared, 0) > s.maxStorage {
		http.Error(w, "server storage is full, try again later", http.StatusInsufficientStorage)
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, s.maxUpload)

	token, err := randomToken()
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	path := s.pathFor(token)
	// Written to a .part file and renamed when complete, so a half-finished upload can never
	// be downloaded as if it were the whole song.
	part := path + partSuffix

	out, err := os.OpenFile(part, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	written, err := io.Copy(out, r.Body)
	closeErr := out.Close()
	if err != nil || closeErr != nil {
		os.Remove(part)
		if errors.As(err, new(*http.MaxBytesError)) {
			http.Error(w, "file too large", http.StatusRequestEntityTooLarge)
			return
		}
		http.Error(w, "upload failed", http.StatusBadRequest)
		return
	}
	if err := os.Rename(part, path); err != nil {
		os.Remove(part)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if declared < 0 {
		s.addBytes(ip, written) // chunked upload: account for the real size afterwards
	}

	// Optional original extension, used for the download's file name.
	if ext := r.URL.Query().Get("ext"); isValidExt(ext) {
		_ = os.WriteFile(s.extPathFor(token), []byte(ext), 0o600)
	}

	// Caller-chosen TTL (capped at maxTTLHours) is recorded in a sidecar file; uploads
	// without a valid ttlHours param fall back to the sweeper's global default TTL.
	if hoursStr := r.URL.Query().Get("ttlHours"); hoursStr != "" {
		if hours, err := strconv.Atoi(hoursStr); err == nil && hours > 0 {
			if hours > s.maxTTLHours {
				hours = s.maxTTLHours
			}
			expiresAt := time.Now().Add(time.Duration(hours) * time.Hour).Unix()
			_ = os.WriteFile(s.expPathFor(token), []byte(strconv.FormatInt(expiresAt, 10)), 0o600)
		}
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"token":"` + token + `"}`))
}

func (s *server) handleDownload(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	token := filepath.Base(r.URL.Path)
	if !isValidToken(token) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	path := s.pathFor(token)

	f, err := os.Open(path)
	if err != nil {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	defer f.Close()

	s.markInflight(token, true)
	defer s.markInflight(token, false)

	info, err := f.Stat()
	if err != nil {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}

	name := "song"
	if ext, err := os.ReadFile(s.extPathFor(token)); err == nil && isValidExt(string(ext)) {
		name += "." + string(ext)
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, name))

	// ServeContent answers Range requests, so an interrupted download resumes where it
	// stopped. The link stays single-use: the file is deleted once a response has delivered
	// the last byte (a whole-file request, or the final range of a resumed one).
	start, reachesEnd := rangeStart(r.Header.Get("Range"), info.Size())
	cw := &countingWriter{ResponseWriter: w}
	http.ServeContent(cw, r, "", info.ModTime(), f)

	if reachesEnd && cw.status < 300 && cw.n == info.Size()-start {
		f.Close()
		os.Remove(path)
		os.Remove(s.expPathFor(token))
		os.Remove(s.extPathFor(token))
	}
}

// countingWriter records the status and how many body bytes were actually delivered.
type countingWriter struct {
	http.ResponseWriter
	status int
	n      int64
}

func (c *countingWriter) WriteHeader(code int) {
	c.status = code
	c.ResponseWriter.WriteHeader(code)
}

func (c *countingWriter) Write(b []byte) (int, error) {
	if c.status == 0 {
		c.status = http.StatusOK
	}
	n, err := c.ResponseWriter.Write(b)
	c.n += int64(n)
	return n, err
}

// rangeStart parses a single "bytes=a-b" / "bytes=a-" range: where it starts and whether it
// runs to the end of the file. No header means the whole file. Multi-range or suffix forms
// are served by ServeContent but never count as "finished" (the sweeper cleans those up).
func rangeStart(header string, size int64) (int64, bool) {
	if header == "" {
		return 0, true
	}
	spec, ok := strings.CutPrefix(header, "bytes=")
	if !ok || strings.Contains(spec, ",") {
		return 0, false
	}
	from, to, found := strings.Cut(spec, "-")
	if !found || from == "" {
		return 0, false
	}
	start, err := strconv.ParseInt(from, 10, 64)
	if err != nil || start < 0 || start >= size {
		return 0, false
	}
	if to == "" {
		return start, true
	}
	end, err := strconv.ParseInt(to, 10, 64)
	if err != nil {
		return 0, false
	}
	return start, end >= size-1
}

// GET /info returns the limits, so clients can adapt TTL choices, size warnings and batch sizes.
func (s *server) handleInfo(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	info := map[string]any{
		"version":                  serverVersion,
		"maxUploadMB":              s.maxUpload >> 20,
		"maxTTLHours":              s.maxTTLHours,
		"maxManifestSongs":         maxManifestSongs,
		"rateLimitMBPerHour":       s.rateLimitBytes >> 20,
		"rateLimitRequestsPerHour": s.rateLimitRequests,
		"rangeDownloads":           true,
	}
	if s.updates != nil {
		if latest := s.updates.Latest(); latest != "" {
			info["latestVersion"] = latest
			info["updateAvailable"] = newerVersion(latest, serverVersion)
		}
	}
	_ = json.NewEncoder(w).Encode(info)
}

// manifestSongEntry holds the same fields as a single-song link; a manifest is a list of them.
type manifestSongEntry struct {
	Token  string `json:"token"`
	Title  string `json:"title"`
	Artist string `json:"artist"`
	Mime   string `json:"mime"`
	Ext    string `json:"ext"`
}

type manifestUploadRequest struct {
	TTLHours int                 `json:"ttlHours"`
	Songs    []manifestSongEntry `json:"songs"`
}

// POST /manifest uses the same auth and rate limit as /upload. Song tokens aren't checked; a
// stale one returns 404 when it is downloaded.
func (s *server) handleManifestCreate(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !s.authorized(r) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	ip := s.clientIP(r)
	if !s.allowRequest(ip, 0) {
		w.Header().Set("Retry-After", "3600")
		http.Error(w, "rate limit exceeded, try again later", http.StatusTooManyRequests)
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxManifestBodyBytes)

	var req manifestUploadRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if len(req.Songs) == 0 || len(req.Songs) > maxManifestSongs {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	for _, entry := range req.Songs {
		if !isValidToken(entry.Token) {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
	}

	hours := req.TTLHours
	if hours <= 0 || hours > s.maxTTLHours {
		hours = s.maxTTLHours
	}

	token, err := randomToken()
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	blob, err := json.Marshal(req.Songs)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if err := os.WriteFile(s.manifestPathFor(token), blob, 0o600); err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	expiresAt := time.Now().Add(time.Duration(hours) * time.Hour).Unix()
	if err := os.WriteFile(s.manifestExpPathFor(token), []byte(strconv.FormatInt(expiresAt, 10)), 0o600); err != nil {
		_ = os.Remove(s.manifestPathFor(token))
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"token":"` + token + `"}`))
}

// GET /manifest/{token} needs only the token. Unlike file downloads it can be read more than once,
// so the recipient can retry a failed download until the manifest expires.
func (s *server) handleManifestGet(w http.ResponseWriter, r *http.Request) {
	setCORS(w)
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	token := filepath.Base(r.URL.Path)
	if !isValidToken(token) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	if expiresAt, ok := s.readManifestExpiry(token); !ok || time.Now().After(expiresAt) {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}

	blob, err := os.ReadFile(s.manifestPathFor(token))
	if err != nil {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(blob)
}

func (s *server) manifestPathFor(token string) string {
	return filepath.Join(s.manifestDir, token+".json")
}

func (s *server) manifestExpPathFor(token string) string {
	return filepath.Join(s.manifestDir, token+".exp")
}

func (s *server) readManifestExpiry(token string) (time.Time, bool) {
	data, err := os.ReadFile(s.manifestExpPathFor(token))
	if err != nil {
		return time.Time{}, false
	}
	unixSec, err := strconv.ParseInt(strings.TrimSpace(string(data)), 10, 64)
	if err != nil {
		return time.Time{}, false
	}
	return time.Unix(unixSec, 0), true
}

func (s *server) sweepManifestsOnce() {
	entries, err := os.ReadDir(s.manifestDir)
	if err != nil {
		return
	}
	for _, e := range entries {
		name := e.Name()
		if e.IsDir() || !strings.HasSuffix(name, ".json") {
			continue
		}
		token := strings.TrimSuffix(name, ".json")
		if !isValidToken(token) {
			continue
		}
		if expiresAt, ok := s.readManifestExpiry(token); !ok || time.Now().After(expiresAt) {
			_ = os.Remove(s.manifestPathFor(token))
			_ = os.Remove(s.manifestExpPathFor(token))
		}
	}
}

func (s *server) authorized(r *http.Request) bool {
	auth := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if len(auth) <= len(prefix) || auth[:len(prefix)] != prefix {
		return false
	}
	got := []byte(auth[len(prefix):])
	want := []byte(s.uploadToken)
	return subtle.ConstantTimeCompare(got, want) == 1 && len(got) == len(want)
}

func (s *server) pathFor(token string) string {
	return filepath.Join(s.dataDir, token)
}

// clientIP is the address rate limits are counted against. Forwarded headers are trusted only
// when TRUST_PROXY says a proxy in front sets them; otherwise any client could fake them.
//   - cloudflare: the CF-Connecting-IP header (Cloudflare Tunnel or proxy).
//   - proxy: the last X-Forwarded-For entry, which the proxy in front (Caddy, nginx) appends.
//   - none: the connecting address, for a server that clients reach directly.
func (s *server) clientIP(r *http.Request) string {
	switch s.trustProxy {
	case "cloudflare":
		if ip := r.Header.Get("CF-Connecting-IP"); ip != "" {
			return ip
		}
	case "proxy":
		if fwd := r.Header.Get("X-Forwarded-For"); fwd != "" {
			parts := strings.Split(fwd, ",")
			return strings.TrimSpace(parts[len(parts)-1])
		}
	}
	// Without the port, so every connection from one client counts as the same client.
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// allowRequest counts one request plus its declared size against the client's hourly budget.
func (s *server) allowRequest(ip string, bytes int64) bool {
	s.ipMu.Lock()
	defer s.ipMu.Unlock()

	now := time.Now()
	lim, ok := s.ipLimiters[ip]
	if !ok || now.Sub(lim.windowStart) > rateLimitWindow {
		lim = &ipLimiter{windowStart: now}
		s.ipLimiters[ip] = lim
	}
	if lim.requests >= s.rateLimitRequests || lim.bytes+bytes > s.rateLimitBytes {
		return false
	}
	lim.requests++
	lim.bytes += bytes
	return true
}

func (s *server) addBytes(ip string, bytes int64) {
	s.ipMu.Lock()
	defer s.ipMu.Unlock()
	if lim, ok := s.ipLimiters[ip]; ok {
		lim.bytes += bytes
	}
}

// storageUsed sums the stored song files (manifests and sidecars are negligible).
func (s *server) storageUsed() int64 {
	entries, err := os.ReadDir(s.dataDir)
	if err != nil {
		return 0
	}
	var total int64
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		if info, err := e.Info(); err == nil {
			total += info.Size()
		}
	}
	return total
}

func (s *server) sweepIPLimiters() {
	s.ipMu.Lock()
	defer s.ipMu.Unlock()
	cutoff := time.Now().Add(-rateLimitWindow)
	for ip, lim := range s.ipLimiters {
		if lim.windowStart.Before(cutoff) {
			delete(s.ipLimiters, ip)
		}
	}
}

func (s *server) markInflight(token string, active bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if active {
		s.inflight[token] = struct{}{}
	} else {
		delete(s.inflight, token)
	}
}

func (s *server) isInflight(token string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, ok := s.inflight[token]
	return ok
}

func (s *server) sweepLoop(ttl time.Duration) {
	ticker := time.NewTicker(sweepInterval)
	defer ticker.Stop()
	for range ticker.C {
		s.sweepOnce(ttl)
		s.sweepManifestsOnce()
		s.sweepIPLimiters()
	}
}

func (s *server) sweepOnce(ttl time.Duration) {
	entries, err := os.ReadDir(s.dataDir)
	if err != nil {
		return
	}
	defaultCutoff := time.Now().Add(-ttl)
	for _, e := range entries {
		// Uploads that never finished (client gone) leave a .part behind.
		if strings.HasSuffix(e.Name(), partSuffix) {
			if info, err := e.Info(); err == nil && time.Since(info.ModTime()) > staleParts {
				_ = os.Remove(filepath.Join(s.dataDir, e.Name()))
			}
			continue
		}
		if e.IsDir() || !isValidToken(e.Name()) {
			continue
		}
		if s.isInflight(e.Name()) {
			continue
		}

		expired := false
		if expiresAt, ok := s.readExpiry(e.Name()); ok {
			expired = time.Now().After(expiresAt)
		} else {
			info, err := e.Info()
			if err != nil {
				continue
			}
			expired = info.ModTime().Before(defaultCutoff)
		}

		if expired {
			_ = os.Remove(filepath.Join(s.dataDir, e.Name()))
			_ = os.Remove(s.expPathFor(e.Name()))
			_ = os.Remove(s.extPathFor(e.Name()))
		}
	}
}

// readExpiry reads the caller-chosen TTL sidecar for a token, if one was written at upload time.
func (s *server) readExpiry(token string) (time.Time, bool) {
	data, err := os.ReadFile(s.expPathFor(token))
	if err != nil {
		return time.Time{}, false
	}
	unixSec, err := strconv.ParseInt(strings.TrimSpace(string(data)), 10, 64)
	if err != nil {
		return time.Time{}, false
	}
	return time.Unix(unixSec, 0), true
}

func (s *server) expPathFor(token string) string {
	return filepath.Join(s.dataDir, token+".exp")
}

func (s *server) extPathFor(token string) string {
	return filepath.Join(s.dataDir, token+".ext")
}

func isValidExt(ext string) bool {
	if ext == "" || len(ext) > 8 {
		return false
	}
	for _, c := range ext {
		if !((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
			return false
		}
	}
	return true
}

func randomToken() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

func isValidToken(s string) bool {
	if len(s) != 32 {
		return false
	}
	for _, c := range s {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
			return false
		}
	}
	return true
}
