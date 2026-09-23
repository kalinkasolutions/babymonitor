# Babymonitor — plan

Two Android phones running the same app. At any moment one is **recording** — sitting in the
nursery with its camera and microphone — and one or more are **observing**, listening and
raising the alarm. Neither phone *is* one or the other: the mode is a runtime state, switchable
from either end and swappable mid-session without re-pairing. A small .NET backend exists only
so the phones can find each other when they are not on the same WiFi.

Native Android, not a PWA: the recording phone has to capture with the screen off and
survive Doze, and the observing phone has to sound an alarm through Do Not Disturb.
Neither is possible on the web platform.

Media is WebRTC and is encrypted end to end with DTLS-SRTP. The backend never sees
decrypted audio or video, not even when it relays.

---

## TL;DR — everything the app does

**Connection.** Direct on the same WLAN, direct across the internet via STUN hole
punching, or relayed through TURN — chosen automatically by ICE, in that order.
Discovery works over the LAN without the backend, so a WAN outage does not stop the
monitor. The observing phone always shows which path is in use. A LAN-only mode refuses
the server entirely. Reconnects by itself, and restarts ICE when the network changes.

**Recording phone.** Foreground service with camera and microphone types. Captures with
the screen off and the device locked. Black overlay so the room stays dark. Starts on
boot, restarts after being killed, pinned so it cannot be closed by accident. Reports
its battery and charging state.

**Observing phone.** Live audio with the screen off. A noise meter to watch the room
without listening to it. An alarm that bypasses silent mode and Do Not Disturb, turns
the screen on by itself, shows over the lock screen, and keeps sounding until someone
acknowledges it. The video can be watched on the lock screen without unlocking.

**Video.** Off by default, because audio-only is dramatically cheaper in battery and
bandwidth. Switched on from the observing phone mid-session, including remotely on a
locked recording phone. Adaptive bitrate. Front and back camera switchable from the
other end. Because phones have no infrared, a dark room needs visible light: a night
light, the recording phone's screen dimmed to red, or the torch — all controlled
remotely.

**Alerts.** Noise above an adjustable threshold. Recording phone gone offline — the most
important one. Battery low or critical at either end. A phone coming off its charger.
Network lost.

**Battery.** Both phones transmit their level and charging state to the other, on change
and on a slow heartbeat, over the data channel while connected and through the backend
otherwise. Both directions matter: a recording phone that dies stops the monitor, and an
observing phone that dies sleeps through the alarm. Coming off the charger is reported
as its own event rather than as a slowly falling number.

**Backend (.NET).** A SignalR hub for signalling, a device registry for pairing,
short-lived TURN credentials, FCM push to wake a disconnected observing phone, and
heartbeat tracking. It stores no media and can decrypt none.

**Later.** Talk-back to the nursery, remote lullaby and white noise, several observing
phones at once with shared alarm acknowledgement, several recording phones, automatic
video when noise is detected, snapshots, a noise timeline for the night, a quick
settings tile, and a diagnostics screen.

The tickable version of this list lives in [FEATURES.md](FEATURES.md).

---

## Encryption and pairing

**Media encryption is not optional and not something to build.** WebRTC requires SRTP with keys
negotiated over DTLS between the two phones (RFC 8827); there is no unencrypted mode to
accidentally end up in. A TURN relay forwards ciphertext it holds no key for. Nothing ever
crosses the internet in the clear.

What is *not* free is knowing **who** you are encrypted to. The SDP carries an
`a=fingerprint:sha-256` line, and anyone who can rewrite it in flight can substitute their own
and become a real man in the middle. Three things follow:

- Signalling runs over HTTPS/WSS. Non-negotiable.
- TLS protects the wire but not the server, so a compromised backend could otherwise swap
  fingerprints without either phone noticing.
- So every signalling message is signed by the sending phone's identity key, and the receiving
  phone checks it against the key it confirmed off that phone's screen. The fingerprint is inside
  what is signed. The backend relays the signature and cannot make one — the private half never
  leaves the keystore — so it can drop a call or delay it, and cannot listen to one.

A message that does not verify is dropped rather than acted on, which means a phone whose key
nobody has confirmed cannot be listened to at all. That is the same bar the microphone already
had, applied to both ends of the call instead of one: the phone being watched must trust who is
watching, and the phone watching must trust what it is being sent, or a substituted stream is
indistinguishable from a quiet room.

### Linking and trust are two different things

**A link decides what you can see.** Two accounts are linked by scanning a code, typing one, or
accepting an emailed invitation — the person who was invited follows the link in the mail, since
an account that already exists is not the inviter's to give away. Phones on one account see each
other with no link needed — they already share an account. Links are not transitive: linking to a
babysitter does not introduce them to your partner.

**Trust decides what you can believe**, and it is a property of one pair of phones, held on the
phones themselves. Each device generates an identity key in the Android keystore where the
private half cannot be exported, so a key belongs to one install on one phone for as long as it
lives.

| how they met | link | trust |
|--------------|------|-------|
| same account | automatic | 1 of 2 |
| typed code | yes | 1 of 2 |
| emailed invitation | once accepted | 1 of 2 |
| scanned QR code | yes | 2 of 2 |

**1 of 2** means the backend vouched for the key and nothing else did. **2 of 2** means somebody
read the key off the other phone's screen and it matched. A code short enough to say out loud
cannot carry a key at all, which is why only the QR reaches 2 of 2 — and why an invitation sent
to somebody in another town always starts at 1 of 2 and is upgraded when you are next together.

One scan settles both phones. The QR carries a one-time secret as well as the key; the phone that
scans signs its own identity with that secret, and the backend relays a signature it can neither
check nor forge, because the secret went from a screen to a camera and never near the server. So
the scanner believes what it read directly, and the phone that was scanned believes the proof.
Nobody has to scan back. What cannot be proved is never quietly counted as a confirmation: the
phone that showed the code says so instead, since a proof that goes missing is exactly what a
backend putting its own key in the middle would produce.

The other way to reach 2 of 2 needs no camera: both phones show a short number derived from the
two keys, and the two people check it is the same. A substituted key would make the two phones
hash different pairs, so matching numbers confirm both keys at once. That is the route back from
a key that changed, and the only route for two people who are never in the same room.

Note that phones on your own account start at 1 of 2 like everything else. The only reason they
appear to belong together is that the backend says so, and that is precisely the claim a scan
exists to check. Treating them as trusted because they share a password would be a convenience
dressed up as a guarantee.

The trust verdict is stored on each phone and never sent anywhere, so a compromised backend
cannot grant it. If the server later reports a different key for a phone that was confirmed, the
app says the key changed rather than going along with it. A reinstall does exactly this, because
a new install really is a new identity.

---

## Use cases

### 1. Setup, once

Both phones install the same app and sign in to the same account. Pairing is symmetric:
either phone can show a QR code and either can scan it. The scan exchanges long-term
identity keys out of band, which is what makes the media path verifiable later.

Someone who is not in the room is added the other way, by entering their email address
and sending an invitation — a second parent, or a babysitter. If they already have an
account, the mail asks them first; nothing is linked until they say yes.

Both phones then grant camera and microphone permission, because either may end up
recording, and both are walked through the battery optimisation exemption, since that
single setting is the most common reason these apps die silently overnight.

The pairing is stored on both devices. From here on they can find each other on the LAN
with no backend involvement at all.

### 2. A normal night — the 95% case

The recording phone is plugged in on a shelf in the nursery, screen black. The observing
phone is on the bedside table, screen off, audio streaming quietly.

Both are on the home WiFi, so they found each other over mDNS and the audio goes
directly between them. The backend is not involved and the internet is not required.
Battery on the observing phone lasts the night because audio-only is around 32 kbps.

### 3. The baby cries

The recording phone's noise level crosses the configured threshold and stays there
longer than the configured delay. It signals the observing phone, which raises the
alarm: the screen turns itself on, a full-screen alert appears over the lock screen, and
a sound plays on the alarm audio stream so it is heard even if the phone is on silent.

The alarm keeps sounding until it is acknowledged. A parent still half asleep does not
have to unlock anything — the noise meter and the video are both on the lock screen.

### 4. Turning the video on

Half awake, the parent wants to see rather than guess. One tap on the lock screen turns
video on. The recording phone, still locked with its screen off, opens the camera and
starts sending.

This works because the foreground service is already running for audio — the camera is
opened inside a service that is already in the foreground, not started cold from the
background, which Android would refuse.

The room is dark, though, so the camera alone would send a black rectangle. Light comes
from one of three places, in order of preference: a night light already in the room, the
recording phone's own screen turned to a dim red, or the torch. See
[Light in a dark room](#light-in-a-dark-room) — this is the weakest part of a
phone-based monitor and it needs a deliberate choice rather than a default.

The parent watches, sees the baby is fine, turns video back off, and the session drops
back to audio and darkness.

### Light in a dark room

Phones cannot do night vision. Real baby monitors illuminate with infrared LEDs and use
a camera with no IR-cut filter; every phone camera has that filter bonded over the
sensor, so an external IR lamp will not rescue this. Some front cameras leak a little
IR — point a TV remote at the camera and look for purple flashes to find out — but it is
not something to design around.

So the room needs visible light whenever video is wanted.

**A night light in the room.** The best image, no engineering, and most nurseries have
one. It also frees the rear camera, since nothing on the phone has to do the lighting.
Documented as a setup recommendation rather than built.

Showing any of this on a locked phone takes permission the system does not give out at install.
"Display over other apps" is the one that works: it is the documented exemption from the ban on
starting a screen from the background. A full-screen intent — the alarm clock's route — is granted
separately and, on current Android, still only produces a notification somebody has to tap, which
is no use to a room nobody is in.

**The recording phone's screen.** The screen and the front camera face the same way, so
the screen lights exactly what the front camera sees, and a full white panel is a real
light source rather than a token one. A 6.5" screen at around 800 nits puts out roughly
26 lumens — more than most night lights — giving about 8 lux at one metre and 4 lux at
one and a half. A decent sensor is comfortable from about 10 lux and usable down to
three or four, so within a metre of the crib the picture is genuinely watchable. Beyond
two metres it is not. Placement decides this.

The limit is not brightness, it is the baby. That much white light aimed into a crib
wakes people. So the screen works as two modes, not one:

- **Glance** — full white at maximum brightness for a few seconds while the parent
  looks, then black again. Brief enough to be a glow rather than a lit room, and it
  avoids the battery and panel heat of running white continuously.
- **Watch** — dim red, for leaving video on. Far less light and a much worse image, but
  nobody wakes up.

**The torch.** The brightest option and the only one that uses the rear camera, which is
the better sensor. Roughly 50 to 100 lumens from a directional source, a few hundred lux
at a metre — an excellent image, and enough to wake anyone. Burst use only: torch LEDs
heat fast and phones throttle or cut them after sustained use.

Control is straightforward but has two shapes. Idle, it is
`CameraManager.setTorchMode()`, which needs no permission at all. While the camera is
streaming that call is ignored, and the flash is driven from inside the capture session
with `CaptureRequest.FLASH_MODE = FLASH_MODE_TORCH` instead. Dimming arrived in Android
13 with `turnOnTorchWithStrengthLevel()`, but only where
`FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` is greater than one — plenty of devices report one
and offer on/off only, so both paths have to exist.

**The light source picks the camera.** The panel faces the same way as the front lens
and the torch faces the same way as the rear lens, so there are only two workable
pairings:

| light | camera | image quality | disruption   |
|-------|--------|---------------|--------------|
| none  | rear   | best          | none         |
| torch | rear   | very good     | high         |
| screen| front  | mediocre      | low          |

Screen with the rear camera, or torch with the front camera, lights the wall behind the
phone. This is geometry, not preference.

This has one structural consequence. Using the screen as a light means the recording
phone needs a lock-screen activity, not only a headless service — the same mechanism
Maps uses for navigation: `setShowWhenLocked` and `setTurnScreenOn` on an activity.

There is no public API to turn a screen off, but none is needed. What matters is emitted
light, not the power state, and on an OLED panel a black window at brightness zero emits
nothing because the pixels are simply unlit. So the activity never sleeps or wakes; it
only changes what it draws and how brightly:

| state     | layout  | screenBrightness |
|-----------|---------|------------------|
| listening | black   | 0f               |
| glance    | white   | 1.0f             |
| watch     | dim red | ~0.1f            |

No wake and sleep round trip means no latency and nothing that can fail on the path that
runs all night.

On an LCD the backlight stays lit and leaves a visible grey glow, so those phones need a
real screen off: `DevicePolicyManager.lockNow()` with the app registered as a device
admin, granted once during setup. Worth confirming that still behaves on the target
Android version, since device admin keeps getting narrowed for non-management apps.

### 5. The parent leaves the house

A babysitter is at home with the baby. The parent is at a restaurant on mobile data.

The phones can no longer find each other on the LAN, so signalling goes through the
backend. STUN hole punching is attempted first. Carrier-grade NAT on mobile networks is
often symmetric, so this is the scenario most likely to fall back to the TURN relay —
and the one that determines whether a TURN server is worth running at all.

The observing phone says plainly that the connection is relayed, and warns before
turning video on, because relayed video is the only configuration that costs real
bandwidth.

### 6. Walking out of WiFi range

The parent steps into the garden and the phone hands over from WiFi to mobile. The
connection drops, ICE restarts, and the session comes back on whatever path now works —
usually direct over the internet, occasionally relayed. Audio resumes without anyone
touching anything.

### 7. The internet goes down

The router is still up and both phones are still on the WiFi, but the WAN is dead. The
backend is unreachable.

Nothing happens. Discovery went over mDNS and the media path is direct on the LAN, so
neither depends on the internet. This case is the reason LAN signalling exists rather
than routing everything through the backend for simplicity.

A household running the backend on its own LAN gets the same outcome from split-horizon DNS —
the name resolves to a machine inside the house, so nothing has to leave it. mDNS is what covers
the arrangement where the backend is somewhere else, and the network it has to work on is one
nobody here administers.

### 8. The recording phone dies

Its charger is knocked out and the battery drains, or Android kills the process, or
someone closes the app.

Below a battery threshold the recording phone warns the observing phone while it still
can, and losing the charger is reported the moment it happens rather than waiting for the
percentage to fall — a phone in a nursery is meant to be plugged in, so the unplugging is
the event, not the drain that follows. If it disappears without warning, the heartbeat stops and the observing phone
raises the offline alarm after a short grace period. A monitor that fails quietly is
worse than no monitor, so this is treated as an alarm, not a status change.

### 9. Both parents listening

Two observing phones are paired to the same recording phone and both receive the stream.
When the alarm fires it fires on both. Whoever gets up first acknowledges it, and the
other phone goes quiet.

### 10. Swapping which phone watches

One parent goes to bed and hands over. Rather than physically swapping handsets, the
phone that was observing is put in the nursery and switched to recording, and the other
switches to observing — from either end, without re-pairing and without signing out.

This is why neither device has a fixed role. The only thing that differs between the two
is which mode each is in right now.

### 11. Soothing from another room

The parent presses and holds the talk button and speaks. The audio plays out of the
recording phone's speaker in the nursery. Alternatively they start a lullaby or white
noise on the recording phone remotely and adjust its volume from where they are.

---

## Connection diagrams

### What runs where

```
┌──────────────────────┐                      ┌──────────────────────┐
│  Phone in recording  │                      │  Phone in observing  │
│  mode (nursery)      │                      │  mode (parent)       │
├──────────────────────┤                      ├──────────────────────┤
│ camera + microphone  │                      │ speaker + screen     │
│ foreground service   │                      │ alarm + lock screen  │
│ lock-screen activity │                      │ noise meter + UI     │
│   (screen as light)  │                      │                      │
│ noise detection      │                      │ WebRTC peer          │
│ WebRTC peer          │                      │                      │
└──────────────────────┘                      └──────────────────────┘

┌───────────────────────────────────────────────────────────────────┐
│                        .NET backend                               │
├───────────────────────────────────────────────────────────────────┤
│ SignalR hub — SDP and ICE candidate exchange                      │
│ device registry — pairings, revocation                            │
│ TURN credentials — short-lived, coturn HMAC                       │
│ FCM push — wake a disconnected observing phone                    │
│ heartbeat — presence per device                                   │
│ no media, ever                                                    │
└───────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────┐
│ coturn — separate, optional, only on the relayed path             │
└───────────────────────────────────────────────────────────────────┘
```

### Path A — same WLAN, direct

The normal case. Neither the internet nor the backend is involved.

```
      ┌─────────────────── home WiFi ────────────────────┐
      │                                                   │
 ┌────┴──────┐                                    ┌──────┴────┐
 │ Recording │                                    │ Observing │
 │   phone   │                                    │   phone   │
 └────┬──────┘                                    └──────┬────┘
      │                                                   │
      │  1. discovery + SDP over mDNS / local socket      │
      │◄─────────────────────────────────────────────────►│
      │                                                   │
      │  2. media, direct, host candidates                │
      │◄═════════════════════════════════════════════════►│
      │                                                   │

 internet   not required          latency   ~5–20 ms
 backend    not involved          cost      none
```

### Path B — different networks, direct

Signalling through the backend, then hole punching, then the server drops out.

```
 ┌───────────┐                                    ┌───────────┐
 │ Recording │                                    │ Observing │
 │   phone   │                                    │   phone   │
 └────┬──────┘                                    └──────┬────┘
      │                                                   │
      │        1. SDP + ICE candidates                    │
      │────────────►┌──────────────────┐◄─────────────────│
      │             │ .NET signalling  │                  │
      │             └──────────────────┘                  │
      │                                                   │
      │        2. "what is my public address?"            │
      │────────────►┌──────────────────┐◄─────────────────│
      │             │      STUN        │                  │
      │             └──────────────────┘                  │
      │                                                   │
      │        3. both send outward at once — each NAT     │
      │           opens a return path for the other        │
      │                                                   │
      │        4. media, direct                           │
      │◄═════════════════════════════════════════════════►│
      │                                                   │

 backend   setup only, then idle    latency   ~20–60 ms
 cost      negligible               works     ~80–90% of the time
```

### Path C — relayed

When hole punching fails, typically symmetric or carrier-grade NAT.

```
 ┌───────────┐                                    ┌───────────┐
 │ Recording │                                    │ Observing │
 │   phone   │                                    │   phone   │
 └────┬──────┘                                    └──────┬────┘
      │                                                   │
      │        1. SDP + ICE candidates                    │
      │────────────►┌──────────────────┐◄─────────────────│
      │             │ .NET signalling  │                  │
      │             └──────────────────┘                  │
      │                                                   │
      │        2. all media via the relay                 │
      │◄═══════════►┌──────────────────┐◄════════════════►│
      │             │  coturn (TURN)   │                  │
      │             │  TLS on 443      │                  │
      │             └──────────────────┘                  │
      │                                                   │

 encryption  still end to end — the relay forwards packets it cannot read
 latency     ~40–120 ms
 cost        audio ~9 GB/month, video ~400 GB/month
```

### How the path is chosen

ICE does this on its own. The app supplies the candidates and reports the outcome.

```
                    both phones on the same LAN?
                                 │
                 ┌───────── yes ─┴─ no ──────────┐
                 │                                │
        host candidates match             STUN, hole punch
                 │                                │
                 ▼                       ┌─── ok ─┴─ fail ───┐
          PATH A — LAN direct            │                    │
          normal, free, offline          ▼                    ▼
                                  PATH B — direct       PATH C — relay
                                  cheap, common         costly, rare
```

### Why the relay cannot read anything

Worth stating plainly, because "the server relays the stream" sounds like the server
watches the stream.

```
 Recording phone                                     Observing phone
 ───────────────                                     ───────────────
 encrypt (DTLS-SRTP) ──► ░░░ ciphertext ░░░ ──► decrypt (DTLS-SRTP)
                               │
                               ▼
                         ┌──────────┐
                         │  coturn  │   forwards bytes
                         │          │   holds no key
                         └──────────┘   sees no video
```

Keys are negotiated between the two phones during the DTLS handshake. The relay is never
a party to it. This is why a TURN relay is the right design and a true man-in-the-middle
would be the wrong one — it would cost far more CPU and would put your baby's video in
the clear on a server.

---

## Build order

1. **Prove the assumption.** Confirm the two actual phones can reach each other on the
   actual router. If client isolation is on, everything below changes shape.
2. **Audio on the LAN.** Recording phone, observing phone, mDNS discovery, direct WebRTC
   audio. No backend at all. This is already a usable baby monitor.
3. **Survive the night.** Foreground service, wake locks, boot start, battery
   optimisation flow, offline alarm. This is the part that decides whether it is
   trustworthy.
4. **Alarms.** Noise threshold, DND bypass, lock screen, acknowledgement.
5. **Video on demand.** Toggle, remote start on a locked phone, adaptive bitrate, and
   the illumination question settled — test the IR sensitivity of the actual camera
   early, because the answer decides whether a night light is required equipment.
6. **The backend.** SignalR signalling, pairing registry, FCM push. Now it works away
   from home.
7. **TURN, only if measurement says so.** Run with STUN alone first and log how often
   the direct path fails between the networks actually used. Stand up coturn only if the
   answer is "often enough to matter".
