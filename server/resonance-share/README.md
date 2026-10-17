# Resonance Share server

Resonance can send songs three ways: **Nearby**, **QR code** and **Share over the internet**.
Only the last one uses a server. The app uses the public server `share.yuwixx.dev` unless you
set your own. This folder is that server, so you can run your own copy, on your home network or
on the internet.

## How it works

1. The sender's app uploads the song to the server and gets a link (also shown as a QR code).
2. The receiver opens the link. Resonance downloads the song and saves it.
3. The server deletes the file once it has been downloaded, or when it expires (the sender picks
   1, 24 or 72 hours).

Only someone with the **upload token** can upload. Downloading needs nothing but the link, and
each link works once. Receivers don't need to set anything up; the link already contains the
server's address. They only need to be able to reach the server.

## Pick a setup

| Setup | Who can receive songs | You need | Effort |
|---|---|---|---|
| [A. Local network](#a-local-network) | Phones on the same Wi-Fi as the server | Any computer that stays on (PC, Raspberry Pi, NAS) | Easiest |
| [B. Tailscale](#b-tailscale) | Phones on your Tailscale network, or anyone with Funnel | A free Tailscale account | Easy |
| [C. Own domain with HTTPS](#c-own-domain-with-https-caddy) | Anyone on the internet | A domain and the ability to open ports 80/443 on your router | Medium |
| [D. Cloudflare Tunnel](#d-cloudflare-tunnel) | Anyone on the internet | A domain on Cloudflare; no open ports needed | Medium |
| [Without Docker](#without-docker) | Depends on how you expose it | Go, and optionally systemd | For tinkerers |

## Before you start

1. Install Docker with the Compose plugin: <https://docs.docker.com/engine/install/>.
2. Get this folder onto the computer that will run the server:
   ```sh
   git clone https://github.com/yuw1xx/resonance-app.git
   cd resonance-app/server/resonance-share
   ```
3. Create your settings file and an upload token:
   ```sh
   cp .env.example .env
   openssl rand -hex 32      # copy the output
   ```
   Open `.env` and put the output after `UPLOAD_TOKEN=`. Keep this token private.

## A. Local network

The server listens on port 8080 and phones on your Wi-Fi talk to it directly.

1. Leave the "A" lines in `.env` as they are (`TRUST_PROXY=none`, `PUBLISH=8080`).
2. Start it:
   ```sh
   docker compose up -d --build
   ```
3. Find the computer's local IP address (`ip addr` on Linux, `ipconfig` on Windows), for example
   `192.168.1.50`. Open `http://192.168.1.50:8080/healthz` in a phone browser; it should say `ok`.
4. [Connect the app](#connect-the-app) with `http://192.168.1.50:8080`.

Tips:
- Give the computer a fixed address (a "DHCP reservation" in your router settings), or the
  address in your app and in old links stops working when it changes.
- If the phone can't reach it, allow port 8080 in the computer's firewall
  (for example `sudo ufw allow 8080/tcp`).
- A link only works for phones that can reach the server, so receivers have to be on the same
  network.

## B. Tailscale

[Tailscale](https://tailscale.com) connects your devices into a private network that works from
anywhere, without opening ports.

**Private (your devices and people you invite):**

1. Do setup A on the server computer.
2. Install Tailscale on the server and on every phone that sends or receives, and sign in to
   the same account (or [share the server](https://tailscale.com/kb/1084/sharing) with friends).
3. Use the server's Tailscale address in the app, for example `http://100.101.102.103:8080`, or
   its MagicDNS name, for example `http://myserver:8080`.

**Public with Funnel (anyone with the link, with HTTPS):**

1. In `.env`, set `TRUST_PROXY=proxy` and `PUBLISH=127.0.0.1:8080`, then
   `docker compose up -d --build`.
2. Run `sudo tailscale funnel --bg 8080`. It prints an address like
   `https://myserver.tailnet-name.ts.net`.
3. Use that `https://` address in the app.

## C. Own domain with HTTPS (Caddy)

[Caddy](https://caddyserver.com) sits in front of the server and gets a free HTTPS certificate
automatically.

1. In your domain's DNS settings, point a name such as `share.example.com` at your home's
   public IP address (an `A` record, plus `AAAA` for IPv6).
2. On your router, forward ports **80** and **443** to the server computer.
3. In `.env`, comment out the "A" lines and use the "C" lines:
   ```sh
   COMPOSE_PROFILES=caddy
   SHARE_DOMAIN=share.example.com
   TRUST_PROXY=proxy
   PUBLISH=127.0.0.1:8080
   ```
4. `docker compose up -d --build`. The first start can take a minute while Caddy gets the
   certificate (`docker compose logs -f caddy` shows progress).
5. Check that `https://share.example.com/healthz` says `ok`, then
   [connect the app](#connect-the-app) with `https://share.example.com`.

## D. Cloudflare Tunnel

The server connects out to Cloudflare, and Cloudflare forwards visitors to it. Nothing has to be
opened on your router. This is how `share.yuwixx.dev` runs.

1. Add your domain to Cloudflare (the free plan works).
2. Install [`cloudflared`](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/)
   on any computer and run:
   ```sh
   cloudflared tunnel login
   cloudflared tunnel create resonance-share     # prints a tunnel ID, writes <ID>.json
   cloudflared tunnel route dns resonance-share share.example.com
   ```
3. Copy the `<ID>.json` file it created (in `~/.cloudflared/`) into this folder's `cloudflared/`
   directory. Then:
   ```sh
   cp cloudflared/config.example.yml cloudflared/config.yml
   ```
   In `config.yml`, replace both `<TUNNEL-ID>` with your ID and `share.example.com` with your
   name. The JSON file is a secret; `.gitignore` keeps it out of git.
4. In `.env`, comment out the "A" lines and use the "D" lines:
   ```sh
   COMPOSE_PROFILES=cloudflare
   TRUST_PROXY=cloudflare
   PUBLISH=127.0.0.1:8080
   ```
5. `docker compose up -d --build`, check `https://share.example.com/healthz`, then
   [connect the app](#connect-the-app).

## Without Docker

The server is a single Go program with no dependencies.

```sh
CGO_ENABLED=0 go build -o resonance-share .
UPLOAD_TOKEN=your-token DATA_DIR=./data ./resonance-share
```

To keep it running on Linux, use the included systemd unit:

```sh
sudo cp resonance-share /usr/local/bin/
echo "UPLOAD_TOKEN=your-token" | sudo tee /etc/resonance-share.env
sudo chmod 600 /etc/resonance-share.env
sudo cp resonance-share.service /etc/systemd/system/
sudo systemctl enable --now resonance-share
```

It listens on port 8080 like setup A. To put Caddy, nginx or a tunnel in front, set
`TRUST_PROXY` as described [below](#trust_proxy).

## Connect the app

On every phone that **sends** songs:

1. Open **Settings → Online Services → Internet Share**.
2. Enter your server address (for example `http://192.168.1.50:8080` or
   `https://share.example.com`) and your upload token, then tap **Save**.

Receivers don't change anything. To go back to the public server, clear both fields.

## Settings

All settings go in `.env` (Docker) or the environment (without Docker).

| Setting | Default | What it does |
|---|---|---|
| `UPLOAD_TOKEN` | required | The password for uploading |
| `TRUST_PROXY` | `none` | Where the client's address comes from, see below |
| `PUBLISH` | `8080` | Docker port mapping; `127.0.0.1:8080` keeps the port off the network |
| `TTL_HOURS` | `24` | How long files are kept when the sender doesn't choose |
| `MAX_TTL_HOURS` | `72` | The longest a sender may choose |
| `MAX_UPLOAD_MB` | `500` | Largest single file |
| `RATE_LIMIT_MB_PER_HOUR` | `3072` | Upload volume per client per hour |
| `RATE_LIMIT_REQUESTS_PER_HOUR` | `300` | Requests per client per hour |
| `MAX_STORAGE_GB` | `20` | Disk space for files waiting to be downloaded |
| `DATA_DIR` | `/data` | Where files are stored (without Docker) |
| `LISTEN_ADDR` | `:8080` | Address and port to listen on (without Docker) |

### TRUST_PROXY

Rate limits are counted per client address. Proxies hide that address and pass it on in a
header, but anyone can send that header themselves. So the server only reads it when you say a
proxy is in front:

- `none`: phones connect directly (setup A, private Tailscale, plain binary).
- `proxy`: Caddy, nginx or Tailscale Funnel is in front and adds `X-Forwarded-For`.
- `cloudflare`: Cloudflare is in front and adds `CF-Connecting-IP`.

With `proxy` or `cloudflare`, never expose port 8080 to the network as well, or clients can
fake their address. That is what `PUBLISH=127.0.0.1:8080` is for.

## Checking and maintenance

- `docker compose logs -f app` shows the server log: its settings at startup, and any errors.
- `docker compose ps` shows what is running.
- Updating: `git pull`, then `docker compose up -d --build`.
- Files waiting for download live in the `share_data` Docker volume. They are temporary, so
  there is nothing to back up except `.env` (and `cloudflared/` for setup D).

## Troubleshooting

| What you see | Likely cause |
|---|---|
| The app can't upload | Wrong address or token. Check that `/healthz` opens in the phone's browser, and that `http://` or `https://` matches your setup. |
| `unauthorized` (401) | The upload token in the app doesn't match `.env`. |
| `rate limit exceeded` (429) | Too many uploads from one client this hour; wait, or raise the limits. |
| `file too large` (413) | The song is bigger than `MAX_UPLOAD_MB`. |
| `server storage is full` (507) | Too many files waiting; they free up when downloaded or expired, or raise `MAX_STORAGE_GB`. |
| The receiver gets "not found" (404) | The link was already used or has expired. |
| The receiver can't connect | Their phone can't reach the server: not on the same network (A), not in your tailnet (B), or DNS/ports aren't set up (C/D). |
| The server exits with a `TRUST_PROXY` error | It must be `none`, `proxy` or `cloudflare`. |

## API

For developers; the app uses these.

| Endpoint | |
|---|---|
| `POST /upload?ttlHours=&ext=` | Bearer token. Stores one file, returns `{"token"}` |
| `GET /f/{token}` | Download. Supports `Range` (resumable); deleted after the last byte is delivered |
| `POST /manifest` | Bearer token. Groups uploaded files into one multi-song link |
| `GET /manifest/{token}` | Song list for a multi-song link (reusable until it expires) |
| `GET /info` | Public limits (`maxUploadMB`, `maxTTLHours`, `maxManifestSongs`, rate limits) |
| `GET /healthz` | `ok` |

Tests: `go test ./...`.
