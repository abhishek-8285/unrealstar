# Release checklist (human steps)

Everything code-side is done and green (`cargo test`, `assembleDebug`). This
file lists the steps that happen outside the repo — servers, consoles,
money desks — plus the device eyeballing pass.

## 1. Server deployment

### camstar-api environment

| Variable | Value | Notes |
|---|---|---|
| `CAMSTAR_TURN_URL` | `turn:<host>:3478` | After coturn is up (see below) |
| `CAMSTAR_TURN_SECRET` | hex secret | Same value as coturn's `.env` `TURN_SECRET` |
| `CAMSTAR_ADMIN_TOKEN` | `openssl rand -hex 32` | Enables the KYC review endpoint (§3). Unset = review disabled |
| `CAMSTAR_WEBHOOK_SECRET` | hex secret | Payment webhook HMAC key. Unset = webhooks refused in release |
| `PUBLIC_ORIGIN` | `https://us.avandab.com` | Used in poster/cover URLs |
| `MEDIA_ROOT` | `/srv/camstar/media` (default) | Must exist and be writable; posters land in `posters/` |

**Must stay unset in production** (each is a fail-closed gate):
`CAMSTAR_ALLOW_GUEST_TOKEN`, `CAMSTAR_DEV_KYC`, `CAMSTAR_ALLOW_TEST_PROVIDER`.

### TURN relay (coturn)

One command on any small public VM — see `deploy/coturn/README.md` for the
full walkthrough (firewall ports, cloud-NAT `external-ip`, verification).

After both sides restart, verify: `GET /api/v1/rtc/ice-servers` must return
two entries (STUN + `turn:` with username/credential).

## 2. Play Console

- **Play Integrity** (anti-tamper). Console steps: Play Console → your app →
  *Release → App integrity → Play Integrity API → Link a Google Cloud
  project*; note the **cloud project number**. The app currently does not
  request integrity tokens; enabling it is a code change that starts *after*
  linking: add `com.google.android.play:integrity` to Gradle, request a token
  with a server nonce at sign-in, and add a backend verify endpoint
  (`decodeIntegrityToken`). Treat as a post-launch hardening task — it does
  not block release.
- **Data safety form**: declare account identifiers, photos (posters), and
  media (streams are P2P/relayed, not stored server-side).
- **Content rating questionnaire** + **target API level** as required by the
  track you ship.
- **Closed testing track** first; internal testing is not evidence of
  stability for Play review.

## 3. KYC review staffing

Applications land as `pending` via `POST /api/v1/kyc/submit`. The dev
auto-approve route is inert in release builds (requires `CAMSTAR_DEV_KYC`).
Staff review with the admin endpoint:

```bash
# Approve
curl -X POST https://us.avandab.com/api/v1/kyc/review/1234 \
  -H "X-Admin-Token: $CAMSTAR_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"decision":"approve"}'

# Reject (reason required, shown to the applicant)
curl -X POST https://us.avandab.com/api/v1/kyc/review/1234 \
  -H "X-Admin-Token: $CAMSTAR_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"decision":"reject","reason":"PAN image blurred"}'
```

Rules of the desk: approve only when payer name matches the PAN and the bank
account name; rejections always carry a reason. Rotate
`CAMSTAR_ADMIN_TOKEN` quarterly and after any staffing change (single token,
server-side restart to apply).

## 4. Android release build

- Configure release signing (`signingConfigs.release`) and keep the keystore
  out of the repo.
- `./gradlew :app:assembleRelease`. Release API base is
  `https://us.avandab.com/api/v1`; no other flags required — TURN now comes
  from the server endpoint, not build-time properties (`-Pturn*` remain only
  as an offline fallback for dev/test builds).
- Verify the release APK manifests: no `debug_otp` rendering (debug-gated),
  no `CAMSTAR_ALLOW_TEST_PROVIDER` reliance.

## 5. Device eyeballing (the QA pass a human must do)

Run on a low-end device (lite path) **and** a mid/high-end one (GPU path):

- [ ] **Exposure**: open the setup screen; if the preview looks washed out,
      tap the face to meter and/or drag Exposure to −2 — confirm it holds.
- [ ] **Beauty**: Smoothing / Whitening / Ruddy / Eye-teeth at each level —
      no plastic skin, no halo at the face edge against bright backgrounds.
- [ ] **Reshape**: Slim face + Big eyes, front camera; edges of the face warp
      smoothly, no pinch artifacts at the jaw/hairline.
- [ ] **Makeup tracks**: move your head left/right/tilt — eyeshadow/teeth/
      mouth stay anchored; blink — the eye regions do not lag.
- [ ] **Poster parity**: with slim/eyes + a colour grade active, capture the
      poster; the thumbnail should look like the preview (same look).
- [ ] **TURN**: viewer on mobile data (Wi-Fi off) sees video within a few
      seconds.
- [ ] **PK**: two devices, host A vs host B — badges both sides, gift points
      tick the scoreboard, end-of-PK clears badges.
- [ ] **End stream**: host ends the stream — ended screen shows real duration,
      peak viewers and diamonds (not the old hardcoded numbers).
