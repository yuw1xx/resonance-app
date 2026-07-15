// REFERENCE SPEC — not part of the Android build, not tested against the live relay server.
//
// The Resonance relay server (Go, running in Docker at share.yuwixx.dev) lives outside this
// repo. This file sketches the two new endpoints needed to support sharing multiple songs
// under a single link, following the same "opaque random token -> TTL'd blob" pattern the
// existing /upload + /f/:token handlers already use for single files.
//
// Design: reuse /upload and /f/:token completely unchanged, one call per song. Once the
// client has uploaded every song and collected their file tokens, it POSTs the list here to
// get back one manifest token. The resulting link is:
//
//   resonance://receive?mode=remote-multi&url=<serverUrl>&manifest=<manifestToken>
//
// Unlike /f/:token (single-use, deleted after first download), GET /manifest/:token is
// REUSABLE within its TTL — the recipient can reopen the song list and retry a failed
// download without the sender re-sharing. The individual song files behind each entry's
// "token" are still single-download, exactly as today.
//
// Before integrating, verify/adapt the TODOs below against the real server code — this file
// is written generically since that code isn't visible from here.

package main

import (
	"encoding/json"
	"net/http"
	"time"
)

type ManifestSongEntry struct {
	Token  string `json:"token"`  // existing per-file download token from /upload
	Title  string `json:"title"`
	Artist string `json:"artist"`
	Mime   string `json:"mime"`
	Ext    string `json:"ext"`
}

type ManifestUploadRequest struct {
	TTLHours int                 `json:"ttlHours"`
	Songs    []ManifestSongEntry `json:"songs"`
}

type ManifestUploadResponse struct {
	Token string `json:"token"`
}

// Cap the batch size and total payload so this can't be used to stuff arbitrary data into
// the store via the (client-controlled) title/artist strings. Pick real numbers when
// integrating — these are placeholders.
const (
	maxManifestSongs   = 50
	maxManifestJSONLen = 64 * 1024 // 64 KiB
)

// POST /manifest
// Auth: same Bearer scheme as /upload — TODO(integration): call the real helper instead of
// this stub (e.g. whatever checks Authorization against RemoteShareDefaults.UPLOAD_TOKEN
// equivalent server-side).
//
// Body: ManifestUploadRequest. Does NOT validate that the individual song tokens exist or
// are unexpired — this is a pure metadata store; a stale/expired song token surfaces as a
// 404/410 at GET /f/:token time later, same as it would today.
//
// Response: 200 {"token": "<hex>"}.
func handleManifestCreate(w http.ResponseWriter, r *http.Request) {
	if !checkBearerAuth(r) { // TODO(integration): reuse existing auth helper, name unverified.
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	var req ManifestUploadRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if len(req.Songs) == 0 {
		http.Error(w, "no songs", http.StatusBadRequest)
		return
	}
	if len(req.Songs) > maxManifestSongs {
		http.Error(w, "too many songs", http.StatusBadRequest)
		return
	}

	ttl := time.Duration(req.TTLHours) * time.Hour
	if req.TTLHours <= 0 || ttl > maxTTL { // TODO(integration): reuse the existing maxTTL const from upload.go.
		ttl = maxTTL
	}

	blob, err := json.Marshal(req.Songs)
	if err != nil || len(blob) > maxManifestJSONLen {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	token := generateToken() // TODO(integration): reuse the existing token-gen helper verbatim.

	// TODO(integration): store `blob` keyed by `token` with expiry `ttl`, reusing whatever
	// store /upload already writes into if it's generic enough to hold non-file payloads,
	// otherwise add a small parallel map alongside it with its own expiry sweep mirroring
	// the file store's.
	manifestStore.Put(token, blob, ttl)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(ManifestUploadResponse{Token: token})
}

// GET /manifest/{token}
// No auth — the token itself is the capability, matching /f/:token's model. This also means
// the response reveals every song's title/artist/mime to anyone holding the link, same as a
// single /f/:token link already reveals one song's metadata today.
//
// NOT single-use: reads do not delete the entry, so the list stays browsable until TTL
// expiry (recipient can retry failed downloads without a new link).
//
// 404 if the token never existed or has expired — TODO(integration): confirm whether the
// store can distinguish "expired and purged" from "never existed" and whether that's worth
// a distinct 410, or if collapsing both to 404 (simplest, and consistent with whatever
// /f/:token already does today) is preferred.
func handleManifestGet(w http.ResponseWriter, r *http.Request) {
	token := extractTokenFromPath(r) // TODO(integration): depends on router lib (chi/gin/stdlib 1.22+ pattern).
	blob, ok := manifestStore.Get(token)
	if !ok {
		http.Error(w, "not found", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(blob)
}

// TODO(integration): wire into the router, e.g. for stdlib 1.22+:
//   mux.HandleFunc("POST /manifest", handleManifestCreate)
//   mux.HandleFunc("GET /manifest/{token}", handleManifestGet)
// and apply the same per-IP rate-limiting middleware that already wraps /upload and
// /f/:token.
