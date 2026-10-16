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

Deploy: copy `main.go`/`go.mod`/`Dockerfile` next to the compose file on the server, keep a
`main.go.bak.<unix-time>` of the previous version, then `docker compose up -d --build app`.
Test: `go test ./...`.
