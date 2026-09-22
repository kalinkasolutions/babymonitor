# Babyphone

Two Android phones: one in the room with the baby, one with you. Audio and video go directly
between them, encrypted end to end; the server only introduces them to each other. See
[PLAN.md](PLAN.md) for the design and [FEATURES.md](FEATURES.md) for what is built.

## Contents

- [Install the server](#install-the-server)
- [Install the app](#install-the-app)
- [How the phones connect](#how-the-phones-connect)
- [When something is wrong](#when-something-is-wrong)
- [Development](#development)
- [Reference](#reference)

---

## Install the server

Two containers, one compose file: the .NET app and coturn. **No certificate goes on the Docker
host** — nginx already has one.

You need a Linux host with Docker, an nginx in front, a public domain, and a certificate for it.

### 1. Two DNS names

| name | points at | carries |
|---|---|---|
| `baby.example.com` | nginx | the API, the signalling hub, `turns:` |
| `turn.example.com` | the **Docker host's** public address | STUN and the relay |

Two names because relayed media is UDP straight to the relay and cannot go through a proxy. One
name is enough if nginx and Docker are the same machine.

### 2. Open these ports

| to | port | |
|---|---|---|
| nginx | 443 TCP | the app |
| nginx | 5349 TCP | the relay over TLS |
| Docker host | 3478 UDP **and** TCP | STUN and the relay |
| Docker host | 49160–49200 UDP | relayed media |

### 3. nginx

Copy [deploy/nginx/babyphone.conf](deploy/nginx/babyphone.conf), replace the three placeholders in
it, and include it from the **top level** of `nginx.conf` — beside `events` and `http`, not inside
them, because the `stream` block cannot go anywhere else:

```nginx
include /etc/nginx/babyphone.conf;
```

If your `nginx.conf` already has an `http` block, move the `server` sections from that file into
it and leave the `stream` section where it is.

### 4. Settings

```bash
cp .env.example .env
```

| | |
|---|---|
| `TURN_SECRET` | `openssl rand -base64 32` |
| `TURN_HOST` | `turn.example.com` |
| `TURNS_HOST` | `baby.example.com` |
| `TURN_LISTENING_IP` | the Docker host's own address — must be its real one, not `0.0.0.0` |
| `EMAIL_*` | your SMTP server |
| `EMAIL_BASE_URL` | `https://baby.example.com` |

Behind NAT, also uncomment `external-ip=<public>/<private>` in
[deploy/turn/turnserver.conf](deploy/turn/turnserver.conf).

### 5. Start

```bash
docker compose up -d
```

Keep `./data` — it holds the database and the key ring that keeps phones signed in.

The image comes from Docker Hub, built by
[the release workflow](.github/workflows/docker-publish.yml) when a release is published. To build
it yourself: `docker build -t kalinkasolutions/babyphone:latest .`

### 6. Check

```bash
curl https://baby.example.com/api/auth/status       # {"authenticated":false,...}
nc -zvu turn.example.com 3478                       # from outside the network
docker logs babyphone-turn | grep -i error          # quiet
```

---

## Install the app

```bash
cd android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # to every connected device
```

**Or skip the server entirely.** On the sign-in screen, *Use it without an account*: the phones
pair by scanning, find each other over mDNS, and nothing leaves the house. You lose what a server
is for — inviting a babysitter by email, listening from outside the house, getting back in when a
phone is lost — and keep the monitor.

Then, on both phones:

1. Open the app, set the server address, sign in.
2. Pair them: **Devices → Connect another phone**, show the code on one and scan it with the
   other. One scan confirms both.
3. On the phone that stays in the room: **Monitor → Leave this phone in the room**, and allow
   "display over other apps" when it asks — that is what lets it light the room while locked.
4. From the other phone: **Listen** or **Watch**.

---

## How the phones connect

Three paths, tried in this order. The icon in the corner of the video says which one a call got.

| | how | server's part |
|---|---|---|
| **Same WiFi** | straight to each other | introduces them, nothing more |
| **Different networks** | STUN tells each phone its public address, then they punch through | introduces them, nothing more |
| **Neither works** | the relay forwards between them | carries the media, encrypted, blind |

STUN is a mirror: a phone asks what its address looks like from outside. TURN is a post office for
when two phones cannot reach each other at all — usually mobile networks, where carrier NAT
defeats hole punching. `coturn` is one program doing both.

**Certificates only matter for `turns:`**, the relay on port 5349, which exists so a call can
escape a network that blocks everything but HTTPS. The media is encrypted end to end whichever
path it takes; the relay forwards bytes it has no key for. Without a certificate you lose one
last-resort path and nothing else.

A wildcard is fine. It must be **publicly trusted** — WebRTC checks the relay against its own root
store and cannot be told about a private CA, so an internal certificate means `turns:` silently
never connects.

**The relay refuses private addresses** as peers, so it cannot be used as a doorway into the
network it sits in. That also means it cannot be tested between two phones on one LAN unless you
let them through on purpose:

```bash
# .env — for a test, then clear it again
TURN_TEST_PEERS=10.0.0.0-10.255.255.255
```

Then turn on **Settings → Always use the relay** in the app and watch the icon change.

---

## When something is wrong

| what you see | why |
|---|---|
| Calls never connect, nothing in any log | nginx is missing the `Upgrade`/`Connection` lines, so the hub never opens |
| Connects on WiFi, never from outside | `TURN_HOST` resolves to a private address, or 3478 and 49160–49200 are not forwarded to the Docker host |
| Relay never works from outside | the host is behind NAT and `external-ip` is not set, so it advertises an address nobody can reach |
| `turns:` never connects, `turn:` fine | the certificate is not publicly trusted, or nginx's `stream` block is inside `http { }` |
| Relay stops working after two months | the certificate renewed and nothing restarted coturn — see the renewal hook below |
| Everyone signed out after a redeploy | `./data` was not persisted |
| "Not reachable" next to a phone | that phone has no connection to the server; open the app on it |
| The room stays dark on video | the phone in the room lacks "display over other apps" |

coturn reads its certificate once at startup, so give the renewal a hook:

```bash
# /etc/letsencrypt/renewal-hooks/deploy/babyphone-turn.sh   (chmod +x)
#!/bin/sh
docker restart babyphone-turn
```

---

## Development

```bash
cd backend
dotnet run --project Babyphone.Api        # https://*:5005, schema applied at startup

cd android
./gradlew testDebugUnitTest               # JVM tests
```

In Development the accounts in `SeedUsers` are created at startup, with known passwords and the
email already confirmed. An environment check in `Seed.cs` means a stray `SeedUsers` section in
production cannot create a login.

| email | password | role |
|---|---|---|
| `admin@local` | `admin` | Admin |
| `niggi@local` | `niggi` | User |

With no `Email:SmtpHost` the app still runs: mails are logged and dropped rather than failing what
triggered them. For local testing point it at a catcher on port 1025. The links in those mails
land on the API itself, which renders a small page, because there is no web front end.

A migration:

```bash
dotnet ef migrations add <Name> --project Babyphone.Dal --startup-project Babyphone.Api
```

### Integration tests

`SignallingIntegrationTest` drives a real backend over the same two connections the app uses, and
skips itself when there is none, so an ordinary test run stays green. Start a disposable one on
its own database:

```bash
ConnectionStrings__Babyphone="Data Source=/tmp/babyphone-test.db" \
DataProtection__KeyPath=/tmp/babyphone-test-keys \
ASPNETCORE_ENVIRONMENT=Development ASPNETCORE_URLS=http://127.0.0.1:5199 \
dotnet run --project backend/Babyphone.Api --no-launch-profile
```

Point it elsewhere with `BABYPHONE_URL`. What it cannot cover is the media: that needs two real
phones.

### Both phones at once

Once per phone, in Developer options → Wireless debugging → *Pair device with pairing code*:

```bash
adb pair <phone-ip>:<pair-port>     # the six digits shown on the phone
adb connect <phone-ip>:5555
./gradlew installDebug              # one command, both phones
```

The app defaults to `https://baby.kalinka-work.lqy.ch`, editable on the login screen. That
certificate is from a private CA, so **it has to be installed on each phone as a user
certificate** — both build types trust user anchors for this reason. The debug build also allows
cleartext, so a laptop on the LAN works with no certificate at all.

### Emulator

Only useful for UI work — no real microphone, no Doze, no lock screen.

**Pairing without an account cannot be tested here at all.** An emulator sits behind its own
virtual NAT, so mDNS multicast never crosses between it and the machines on your WiFi, and its
address is not routable from a phone. Two real phones on one network are the only way to exercise
it — which is the case it exists for.

```bash
ls -l /dev/kvm                       # required
sdkmanager --install "emulator" "platform-tools" \
           "system-images;android-36;google_apis;x86_64"
avdmanager create avd -n babyphone \
           -k "system-images;android-36;google_apis;x86_64" -d pixel_7
$ANDROID_HOME/emulator/emulator -avd babyphone -camera-back webcam0 &
```

`google_apis` rather than `google_apis_playstore`, because the Play Store images cannot be rooted.
The webcam lets you hold the other phone up to it to test QR scanning.

---

## Reference

### API

| route | |
|---|---|
| `POST api/auth/register` `login` `login-2fa` `logout` | sign-in |
| `GET api/auth/status` | session check |
| `POST api/auth/resend-confirmation` `forgot-password` `reset-password` | email flows |
| `POST api/auth/invite` | add someone by email address |
| `GET api/auth/confirm-email` `confirm-email-change` `accept-link` `reset-password-form` | followed from a mail, renders a page |
| `GET api/account` · `PUT api/account/username` | account, display name |
| `POST api/account/password` `email` `sign-out-everywhere` | self-service |
| `GET/POST api/account/2fa/…` | two-factor |
| `DELETE api/account` | delete, password required |
| `GET api/devices` · `POST api/devices` · `DELETE api/devices/{id}` | the device registry |
| `POST api/pairing/code` `claim` · `DELETE api/pairing/link/{id}` | pairing and linking |
| `GET api/turn/credentials` | a relay credential, good for twelve hours |

Phones also hold a SignalR connection to `/hubs/devices`: device changes, presence, and the
signalling for a call. A connection that sends `X-Device-Id` joins a group of its own, which is how
one phone can be called rather than every phone on the account.

### Navigation

```
Monitor | Devices | Settings          bottom bar
   avatar top-right  ───────────────▸ Account      pushed, bar hidden
   Devices ▸ Connect another phone ─▸ Connect      pushed, bar hidden
```

The avatar owns **who you are** — email, password, two-factor. Settings owns **how the app
behaves** — alerts, video, the server address.

### If the Docker host has the certificate

When nginx and Docker are the same machine, coturn can terminate its own TLS:

```bash
docker compose -f docker-compose.yml -f docker-compose.turns.yml up -d
```

Set `TURNS_HOST` to `TURN_HOST`, drop the nginx `stream` block, and set `TLS_CERT_PATH`,
`TLS_KEY_PATH` and `TLS_KEY_UID` — the last because the image runs coturn as `nobody`, which
cannot read a mode-600 key.
