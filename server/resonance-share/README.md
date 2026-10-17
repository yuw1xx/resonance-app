# resonance-share

Self-hosted relay for Resonance's long-distance song sharing (`share.yuwixx.dev`).
Upload is token-gated; downloads are capability links that work once.

| Endpoint | |
|---|---|
| `POST /upload?ttlHours=&ext=` | Bearer token. Stores one file, returns `{"token"}` |
| `GET /f/{token}` | Download. Supports `Range` (resumable); deleted after the last byte is delivered |
| `POST /manifest` | Bearer token. Groups uploaded files into one multi-song link |
| `GET /manifest/{token}` | Song list for a multi-song link (reusable until it expires) |
| `GET /info` | Public limits (`maxUploadMB`, `maxTTLHours`, `maxManifestSongs`, rate limits) |
| `GET /healthz` | `ok` |

Configuration (environment, see `docker-compose.yml`): `UPLOAD_TOKEN` (in `.env`, not
committed), `TTL_HOURS`, `MAX_TTL_HOURS`, `MAX_UPLOAD_MB`, `RATE_LIMIT_MB_PER_HOUR`,
`RATE_LIMIT_REQUESTS_PER_HOUR`, `MAX_STORAGE_GB`.

## Running your own

The server is reached through a Cloudflare Tunnel, so it needs no open ports. It trusts the
client IP that Cloudflare reports, so don't expose port 8080 directly.

1. `cp .env.example .env` and set `UPLOAD_TOKEN` to a long random value.
2. Create a tunnel (`cloudflared tunnel create resonance-share`), put its credentials JSON in
   `cloudflared/`, and copy `cloudflared/config.example.yml` to `cloudflared/config.yml` with
   your tunnel ID and hostname.
3. `docker compose up -d --build`.
4. In the app: Settings → Online Services → Internet Share, enter your URL and upload token.

Updating: copy `main.go`/`go.mod`/`Dockerfile` next to the compose file, keep a
`main.go.bak.<unix-time>` of the previous version, then `docker compose up -d --build app`.
Test: `go test ./...`.
