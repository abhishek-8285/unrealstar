# CamStar TURN relay (coturn)

The API hands viewers/hosts STUN + short-lived TURN credentials from
`GET /api/v1/rtc/ice-servers`. STUN lets most peers connect directly; TURN is
the relay that saves the rest — anyone behind bank/hotel/office Wi-Fi or CGNAT
(mobile data!) — by hopping media through this server. Without it those
viewers simply never see video.

This kit provisions that relay in one command. It uses coturn's
`use-auth-secret` mode: the API mints per-user HMAC credentials from a shared
secret, credentials expire on their own, and no static TURN password ever
ships in the app.

## Prerequisites

- A small public server (1 vCPU / 1 GB is plenty for hundreds of viewers —
  only NAT-blocked pairs actually relay). Ubuntu 22.04+ assumed.
- Docker + Docker Compose plugin (`docker compose version` works).
- Firewall / security-group openings:

  | Port | Proto | Why |
  |------|-------|-----|
  | `3478` | UDP **and** TCP | TURN control + relay allocations |
  | `49160–49200` | UDP | relayed media (one port per relayed stream) |
  | `22` | TCP | SSH |

## Quick start (Docker)

On the server, from this directory:

```bash
cp .env.example .env
# Generate the shared secret — same value goes on the API later:
openssl rand -hex 32    # paste into TURN_SECRET in .env
nano .env               # set TURN_SECRET; TURN_REALM can stay camstar.live;
                        # set TURN_EXTERNAL_IP if the VM sits behind cloud NAT
./deploy.sh
```

`deploy.sh` renders `turnserver.conf` (keeping your secret out of git),
starts the relay, and prints the exact next steps. Check health anytime:

```bash
docker compose logs --tail 30 coturn
```

> **Cloud NAT note:** on AWS/GCP/Azure the VM's NIC usually holds a *private*
> IP and the public IP is 1:1-NAT'd. Set `TURN_EXTERNAL_IP=<public ip>`.
> If `ip addr` already shows the public address (most bare VPS providers),
> leave it empty.

## Bare metal alternative (no Docker)

```bash
sudo apt-get update && sudo apt-get install -y coturn
# Optionally build the same config the script generates:
./deploy.sh --render-only
sudo cp turnserver.conf /etc/turnserver.conf
sudo sed -i 's/^#TURNSERVER_ENABLED=1/TURNSERVER_ENABLED=1/' /etc/default/coturn
sudo systemctl enable --now coturn
sudo systemctl status coturn --no-pager
```

## Point the API at the relay

On the API service (same host or wherever `camstar-api` runs), set:

```bash
CAMSTAR_TURN_URL=turn:<public-host-or-ip>:3478
CAMSTAR_TURN_SECRET=<the same hex secret from .env>
```

Restart the API. Until both are set, `/api/v1/rtc/ice-servers` stays
STUN-only — the relay is inert, and nothing breaks.

## Verify

1. **Server sees the secret** (login as any user first to get a token):

   ```bash
   curl -s https://us.avandab.com/api/v1/rtc/ice-servers \
     -H "Authorization: Bearer <token>" | python3 -m json.tool
   ```

   Expect **two** entries: the STUN server, then a `turn:` entry with a
   `username` like `1799999999:1234` and a `credential`. Only STUN? The API
   env vars are missing or the API wasn't restarted.

2. **Relay answers** — compute a credential for a probe user the same way the
   API does and ask coturn to allocate:

   ```bash
   set -a; . ./.env; set +a
   U="$(date -u -d '+1 hour' +%s):probe"
   W="$(printf '%s' "$U" | openssl dgst -binary -sha1 -hmac "$TURN_SECRET" | base64)"
   sudo apt-get install -y coturn      # provides turnutils_uclient
   turnutils_uclient -t -u "$U" -w "$W" -p 3478 <public-host-or-ip>
   ```

   `success: allocate ...` lines mean the relay works. (Or use the browser
   Trickle ICE page with the same `turn:` URL + computed user/password.)

3. **End to end:** put a viewer on mobile data (Wi-Fi off) and open any live
   room. `adb logcat | grep -i "relay\|turn"` on the phone should show relay
   candidates gathering; video should start even with both peers behind NAT.

## Troubleshooting

- **`turnutils_uclient` fails with 401** — the secret in `.env` and the one
  the test computed differ, or `use-auth-secret`/`static-auth-secret` is
  missing from the rendered conf (re-run `./deploy.sh` after editing `.env`).
- **No `turn:` entry in the API response** — server side is fine; it's the
  API's `CAMSTAR_TURN_URL`/`CAMSTAR_TURN_SECRET` that are unset.
- **Connects on Wi-Fi, not on mobile data** — UDP relay ports
  `TURN_MIN_PORT–TURN_MAX_PORT` are not open in the firewall.
- **Viewer behind corporate VPN can't connect at all** — this kit denies
  relaying into private ranges (`denied-peer-ip` lines in
  `turnserver.conf`) so the relay cannot be abused as a tunnel into your
  VPC. Corporate-internal viewers typically cannot be served anyway.
- **Need media over TCP/443** (networks that drop all UDP): add a TLS
  listener (`turns:` on 5349, or 443) with a certificate — a follow-up, not
  required for the Play launch. libwebrtc automatically retries TURN over
  TCP on the same `turn:` URL when UDP allocation fails.
- **More concurrent relayed viewers** — widen `TURN_MIN_PORT`/`TURN_MAX_PORT`
  in `.env` (each port carries one relayed stream) and open the new range.
