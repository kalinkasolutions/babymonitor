# Babymonitor

[![Tests](https://github.com/kalinkasolutions/babymonitor/actions/workflows/tests.yml/badge.svg)](../../actions/workflows/tests.yml)

Two Android phones: one in the room with the baby, one with you. Audio and video go directly
between them, encrypted end to end. On the same WiFi that is all you need. A small server is
optional: it passes messages between the phones when they are on different networks, and relays
the call when nothing else gets through. See
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

**Optional.** Without a server, both phones must be on the same WiFi. With one, you can listen
from anywhere.

The server keeps no accounts and no database. Phones prove who they are with the key they already
have. Anyone who knows the address can use it, except the optional home relay, which needs the
server key.

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
| Docker host | 3480 UDP **and** TCP, 49201–49240 UDP | only with the [home relay](#the-home-relay) |

### 3. nginx

Copy [deploy/nginx/babymonitor.conf](deploy/nginx/babymonitor.conf), replace the three placeholders in
it, and include it from the **top level** of `nginx.conf` — beside `events` and `http`, not inside
them, because the `stream` block cannot go anywhere else:

```nginx
include /etc/nginx/babymonitor.conf;
```

If your `nginx.conf` already has an `http` block, move the `server` sections from that file into
it and leave the `stream` section where it is.

### 4. Settings

```bash
cp .env.example .env
```

| | |
|---|---|
| `PROXY_TRUST_ANY` | `true`, unless nginx is on another machine — see below |
| `APP_BIND_ADDRESS` | `127.0.0.1`, unless nginx is on another machine |
| `TURN_SECRET` | `openssl rand -base64 32` |
| `TURN_HOST` | `turn.example.com` |
| `TURNS_HOST` | `baby.example.com` |
| `TURN_LISTENING_IP` | the Docker host's own address — must be its real one, not `0.0.0.0` |
| `TURN_EXTERNAL_IP` | behind NAT: the public address alone, e.g. `203.0.113.7` — not `public/private`, which lets anyone relay to this host |

coturn needs no config file: every setting is a flag in `docker-compose.yml`, so that file and
`.env` are the whole installation.

#### Telling the app about nginx

nginx terminates TLS and hands the app plain HTTP, so the app only knows the request was
encrypted because nginx says so in `X-Forwarded-Proto`. Anyone who can reach the app's port could
say the same thing, so it is believed only from an address you name — and the framework's own
default is loopback, which inside a container is nobody at all. Get this wrong and nothing breaks
loudly: the app just treats every request as cleartext from nginx's own address, and says so once
at startup.

**Same machine** — the default, and the simple case. The port is bound to `127.0.0.1`, so nothing
but nginx can reach it, and the app believes whatever reaches it:

```bash
APP_BIND_ADDRESS=127.0.0.1      # nothing else can get to the port
PROXY_TRUST_ANY=true
```

**nginx elsewhere** — the port has to be open, so trust an address instead of anyone:

```bash
APP_BIND_ADDRESS=0.0.0.0
PROXY_TRUST_ANY=false
PROXY_TRUSTED=10.0.0.5          # nginx's address, or a CIDR range
```

Which address to name is whatever nginx appears as *from inside the container*, and that depends
on how it reaches the port — a proxy dialling `127.0.0.1` on the same host arrives as the Docker
bridge gateway, one dialling the host's own address arrives as that address, and one on another
machine arrives as itself. `docker logs babymonitor | grep -i proxy` says what was configured.

### 5. Start

```bash
docker compose up -d
```

The image comes from Docker Hub, built by
[the release workflow](.github/workflows/docker-publish.yml) when a release is published. To build
it yourself: `docker build -t kalinkasolutions/babymonitor:latest .`

### 6. Check

```bash
curl https://baby.example.com/api/status            # {"ok":true}
nc -zvu turn.example.com 3478                       # from outside the network
docker logs babymonitor-turn | grep -i error          # quiet
```

---

## Install the app

Download `babymonitor-<version>.apk` from the
[latest release](../../releases/latest) and open it on the phone. Android will ask you to allow
installing from wherever you downloaded it; that prompt is the one-off price of not being on an
app store. Do it on both phones.

**Pick one source and stay on it.** The releases here and the F-Droid build are signed with
different keys — both genuine, neither able to update the other, because Android identifies an app
by its signature. Moving between them means uninstalling first, and uninstalling throws away the
phone's identity key and every pairing with it. Settings → About shows which one you have, as the
short code after the commit.

Or build it yourself:

```bash
cd android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # to every connected device
```

Then, on both phones:

1. Open the app. There is nothing to sign in to.
2. If you run a server: on one phone, **Settings → Server**, enter the address and the server
   key, and press **Save and check**. The other phone gets them when you pair.
3. Pair them: **Devices → Connect another phone**, show the code on one and scan it with the
   other. One scan confirms both. Without a server, both phones must be on the same WiFi for this.
4. On the phone that stays in the room: **Monitor → Check the picture** to find a good spot, then
   **Use this phone as the monitor**. Allow "display over other apps" when it asks — that is what
   lets it light the room while locked. If Android answers "App was denied access", see
   [the fix](#when-something-is-wrong) below.
5. From the other phone: **Listen** or **Watch**. While watching, the corner button makes the
   picture full screen, and **Keep this screen on** stops the phone from locking.

Pairing an older version of the app does not work: the pairing code changed. Update both phones
and pair again.

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

### The home relay

The refusal above has one cost. If the server is in the same house as the phone in the room, a
phone outside can only reach that room phone if the router loops traffic for its own public
address back in ("NAT loopback"). Many routers do not. Then the room phone never learns its public
address, and the only way left is the relay sending to its private one, which it refuses.

The home relay is a second coturn that may reach one private range: your home network. Because
that makes it a doorway into the house, only phones holding the **server key** get a credential
for it, and it has its own secret, so a credential for the open relay does not work on it.

```bash
# .env
COMPOSE_PROFILES=home-relay
SERVER_KEY=...             # openssl rand -base64 24, typed into the app once
HOME_RELAY_SECRET=...      # openssl rand -base64 32, not TURN_SECRET
HOME_RELAY_PEERS=10.0.0.0-10.0.1.255
```

Forward 3480 (UDP and TCP) and 49201–49240 (UDP) to the Docker host. In the app, enter the key under
**Settings → Server key**; pairing copies it to the other phone. If your router does NAT loopback,
you do not need any of this.

---

## When something is wrong

| what you see | why |
|---|---|
| Calls never connect, nothing in any log | nginx is missing the `Upgrade`/`Connection` lines, so the hub never opens |
| Connects on WiFi, never from outside | `TURN_HOST` resolves to a private address, or 3478 and 49160–49200 are not forwarded to the Docker host |
| Relay never works from outside | the host is behind NAT and `TURN_EXTERNAL_IP` is not set, so it advertises an address nobody can reach |
| `turns:` never connects, `turn:` fine | the certificate is not publicly trusted, or nginx's `stream` block is inside `http { }` |
| Relay stops working after two months | the certificate renewed and nothing restarted coturn — see the renewal hook below |
| "The server did not accept the server key" | the key in the app is not `SERVER_KEY` from `.env` |
| "The server did not accept this phone's signature" | the phone's clock is more than five minutes off |
| The room phone is at home, and a phone outside never connects | the router does no NAT loopback — set up the [home relay](#the-home-relay) |
| Every log line says the same client address | `PROXY_TRUST_ANY`/`PROXY_TRUSTED` not set, so `X-Forwarded-For` is ignored |
| "Not reachable" next to a phone | that phone has no connection to the server; open the app on it |
| The room stays dark on video | the phone in the room lacks "display over other apps" |
| "App was denied access" when allowing "display over other apps" | Android restricts that permission for an APK installed from a browser or file manager. Settings → Apps → Babymonitor → ⋮ → **Allow restricted settings**, then turn it on again. The ⋮ entry appears only after the first refusal, and not at all with Advanced Protection on |

coturn reads its certificate once at startup, so give the renewal a hook:

```bash
# /etc/letsencrypt/renewal-hooks/deploy/babymonitor-turn.sh   (chmod +x)
#!/bin/sh
docker restart babymonitor-turn
```

---

## Development

```bash
cd backend
dotnet run --project Babymonitor.Api     # https://*:5005
dotnet test                               # the backend suite

cd android
./gradlew testDebugUnitTest               # JVM tests
```

Both suites also run on every push and pull request
([tests.yml](.github/workflows/tests.yml)), which is the badge at the top of this file. The two
release workflows fire only when a release is published, so without this a suite could go red and
stay red until somebody tried to ship.

`Babymonitor.Tests` boots the real app with `WebApplicationFactory` and talks to it over HTTP
and SignalR, with test phones that sign in with keys generated on the spot.

### Cutting a release

Publishing a release on GitHub builds both halves:
[the backend image](.github/workflows/docker-publish.yml) and
[the APK](.github/workflows/android-release.yml), which is attached to the release for people to
install from.

1. Bump `versionCode` **and** `versionName` in
   [android/app/build.gradle.kts](android/app/build.gradle.kts). Android decides what counts as an
   upgrade by `versionCode`, so publishing the same one twice produces a release nobody can install
   over the last. The workflow refuses if the tag and `versionName` disagree.
2. Tag it to match — `v0.2` for `versionName = "0.2"` — and publish the release.

The APK is a **release** build, never a debug one: a debug build points at a development server,
trusts user-installed certificate authorities and permits cleartext, all of which are right for a
laptop on the LAN and wrong in somebody else's house.

#### The signing key

Four secrets, all describing one keystore. Put them on the workflow's `release` **environment**
rather than on the repository: repository secrets are readable by every workflow in the repo, and
this is the one credential that decides whether a build is the app people already have. An
environment can also be given a protection rule, so a release waits for you to approve it.

| secret | |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | |
| `ANDROID_KEY_ALIAS` | |
| `ANDROID_KEY_PASSWORD` | |

```bash
keytool -genkeypair -v -keystore release.jks -alias babymonitor \
        -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks          # paste into ANDROID_KEYSTORE_BASE64
```

**Back that file up somewhere outside this repository, and never replace it.** Android identifies
an app by its signing key. A release signed with a different key is not an upgrade to the one
people have — it will not install over it, and uninstalling to make room throws away the identity
key in that phone's keystore, and with it every pairing on both phones.

GitHub is not that backup. Secrets are write-only: once set, `ANDROID_KEYSTORE_BASE64` cannot be
read back out, so a copy there is a copy you cannot recover. Keep the `.jks` and its three
passwords together in a password manager, and a second copy offline. It is four kilobytes.

#### Two signatures, on purpose

The app is published twice: here, signed with the project's key, and on F-Droid, signed with
theirs. That is the normal arrangement and both are real. What it costs is that the two
populations never mix — an update from the other source is refused, not installed.

The trap is that F-Droid's client lists an app it did not install as installed and offers the
update anyway, so somebody who installed from GitHub can be walked into a failure with no
explanation attached. Hence the signing key in Settings → About: it is the first eight characters
of the signing certificate, the same value `apksigner` prints, so "which build have you got" has a
short answer somebody can read off a screen.

```bash
apksigner verify --print-certs babymonitor-0.2.apk   # matches what About shows
```

#### Checking a download

Two different questions, and they need different answers.

**"Is this the same app I already have?"** is the one Android asks, on every install. The
certificate inside an APK is self-signed, and there is no authority for Android to consult, so
all it does is compare that certificate to the one already on the phone. The run summary prints its
fingerprint, so a release can be compared against the last:

```bash
apksigner verify --print-certs babymonitor-0.2.apk
```

**"Did this file come out of that repository?"** is the one the signature cannot answer, because
anybody can self-sign. The workflow attests the APK with
[GitHub's build provenance](https://docs.github.com/actions/security-guides/using-artifact-attestations-to-establish-provenance-for-builds),
signed with the run's own short-lived identity rather than any stored key:

```bash
gh attestation verify babymonitor-0.2.apk --repo <owner>/babymonitor
```

That names the workflow, the repository and the commit the APK was built from.

**"Is this really built from that source?"** is the one nobody has to take on trust, because the
release build is reproducible: the same commit, built again, produces the same bytes. The app
says which commit it is under Settings → About, and the attestation above says so independently.
Either way, you can go and check:

```bash
git checkout <that commit>
cd android && ./gradlew clean assembleRelease
./verify-apk.py ~/Downloads/babymonitor-0.2.apk
```

[verify-apk.py](android/verify-apk.py) compares every entry inside the two archives — the code,
the resources, the native libraries. It compares the contents rather than the files, because the
published APK carries a signature block a local build cannot reproduce and should not: that block
*is* the signature, and `apksigner` is what checks it. This checks the other half, that what was
signed is what the source builds.

Reproducibility is a property that breaks quietly — a timestamp or a build number compiled into
the APK is enough — so there is a note on `buildConfigField` in
[build.gradle.kts](android/app/build.gradle.kts) saying so. Verified two ways here: two clean
builds of the same commit, and a build from a different directory, all three byte-identical.

None of this is something a parent installing a monitor will do. It is there so that somebody
*can*, and so that the answer does not rest on trusting this repository, GitHub, or me.

### Integration tests

`SignallingIntegrationTest` drives a real backend over the same connections the app uses, and
skips itself when there is none, so an ordinary test run stays green. Start one:

```bash
ASPNETCORE_URLS=http://127.0.0.1:5199 \
dotnet run --project backend/Babymonitor.Api --no-launch-profile
```

Point it elsewhere with `BABYMONITOR_URL`. What it cannot cover is the media: that needs two real
phones.

### Both phones at once

Once per phone, in Developer options → Wireless debugging → *Pair device with pairing code*:

```bash
adb pair <phone-ip>:<pair-port>     # the six digits shown on the phone
adb connect <phone-ip>:5555
./gradlew installDebug              # one command, both phones
```

The app defaults to `https://baby.kalinka-work.lqy.ch`, editable in Settings. That
certificate is from a private CA, so **it has to be installed on each phone as a user
certificate** — both build types trust user anchors for this reason. The debug build also allows
cleartext, so a laptop on the LAN works with no certificate at all.

### Emulator

Only useful for UI work — no real microphone, no Doze, no lock screen.

**Pairing over the WiFi cannot be tested here at all.** An emulator sits behind its own
virtual NAT, so mDNS multicast never crosses between it and the machines on your WiFi, and its
address is not routable from a phone. Two real phones on one network are the only way to exercise
it — which is the case it exists for.

```bash
ls -l /dev/kvm                       # required
sdkmanager --install "emulator" "platform-tools" \
           "system-images;android-36;google_apis;x86_64"
avdmanager create avd -n babymonitor \
           -k "system-images;android-36;google_apis;x86_64" -d pixel_7
$ANDROID_HOME/emulator/emulator -avd babymonitor -camera-back webcam0 &
```

`google_apis` rather than `google_apis_playstore`, because the Play Store images cannot be rooted.
The webcam lets you hold the other phone up to it to test QR scanning.

---

## Reference

### API

| route | |
|---|---|
| `GET api/status` | `{"ok":true}`, no key needed |
| `GET api/status/key` | 200 with `homeRelay` when `X-Server-Key` is right, 401 when not; 200 on a server without a key |
| `GET api/turn/credentials` | a relay credential, good for twelve hours, and under `homeRelay` a second one when `X-Server-Key` is right |

Every call except the status check carries three headers: `X-Device-Key`, `X-Device-Time`,
`X-Device-Signature` — the phone's public key and its signature over `babymonitor-hub\n<time>`.
`X-Server-Key` is added when the phone has one. A phone's id is the first 16 bytes of SHA-256 over its key, in hex, so
the server needs no list of phones.

Phones also hold a SignalR connection to `/hubs/devices`, signed in the same way:

| method | |
|---|---|
| `Signal(json)` → `bool` | pass a message to one phone; true when it is connected |
| `Watch(ids)` → `ids` | hear when these phones come and go; returns those online now |

It sends `signal` and `devicePresence` back. The server never checks a signal's own signature —
the phones do, against the key they scanned.

### Navigation

```
Monitor | Devices | Settings          bottom bar
   Monitor ▸ Check the picture ─────▸ CheckRoom    pushed, bar hidden
   Devices ▸ Connect another phone ─▸ Connect      pushed, bar hidden
```

### If the Docker host has the certificate

When nginx and Docker are the same machine, coturn can terminate its own TLS:

```bash
docker compose -f docker-compose.yml -f docker-compose.turns.yml up -d
```

Set `TURNS_HOST` to `TURN_HOST`, drop the nginx `stream` block, and set `TLS_CERT_PATH`,
`TLS_KEY_PATH` and `TLS_KEY_UID` — the last because the image runs coturn as `nobody`, which
cannot read a mode-600 key.
