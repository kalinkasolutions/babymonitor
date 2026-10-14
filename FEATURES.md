# Babymonitor — feature checklist

Two Android phones running the same app. At any moment one is **recording** (camera/mic, in
the room) and one or more are **observing** (listening, alerting). The mode is runtime state,
not a property of the device — either phone can do either, and they can swap without
re-pairing. A .NET backend for signalling and relay fallback.

Media is WebRTC, encrypted end-to-end (DTLS-SRTP). The server never sees decrypted
audio or video, even when relaying.

---

## MVP

### Connection

Three media paths, picked automatically by ICE in this order:

- [x] Direct on the same WLAN (ICE host candidates) — the normal case
- [x] Direct across the internet (STUN, server-reflexive candidates) — when away from home
- [x] TURN relay (coturn) — when neither of the above works, and never before
- [x] STUN offered whether or not a relay is configured, because finding your own address
      costs the server nothing and is what keeps media off it
- [x] Short-lived TURN credentials issued by the backend (coturn HMAC scheme)
- [ ] TURN reachable over TLS on 443, not only UDP 3478, so restrictive networks work
- [ ] Warn before relaying video, since it is the only path that costs real bandwidth

Two signalling paths, so the normal case does not depend on the internet:

- [ ] Untested between two real phones: an emulator is behind its own NAT, so mDNS never
      reaches it and the serverless path cannot be exercised with one
- [x] Discovery and SDP exchange over the LAN (Android NSD / mDNS), no backend involved: each
      phone announces itself as `_babymonitor._tcp` and hands the other its offer on a local
      socket, tried before the hub and falling back to it
- [x] Every signalling message signed with the sender's identity key and checked against the key
      this phone confirmed in person, on both paths — the SDP's DTLS fingerprint is inside what is
      signed, so a backend that rewrote it to read the media would be caught rather than obeyed
- [x] A signal that does not verify is dropped, not acted on, so a phone whose key nobody has
      confirmed cannot be listened to — the same bar the microphone had, now at both ends
- [x] Signed messages carry the time they were signed, so one captured in flight cannot be
      replayed later — a replayed "stop" would end a call
- [x] Signalling through the backend when the phones are on different networks — offer,
      answer and candidates, addressed to one phone by an id derived from its key
- [x] Keep working when the WAN is down but the WiFi is up — signalling over the LAN, media
      direct, neither touching the internet

First calls — audio working on two real phones, video built on the same path:

- [x] One peer connection carrying audio, and video when the call asked for it
- [x] WebRTC audio processing turned off, because echo cancellation and noise suppression
      remove exactly what a monitor listens for
- [x] The phone that captures is the one that offers; the phone that asked, answers
- [x] Candidates that arrive before the description they belong to wait rather than being lost
- [x] One call at a time; a second phone asking is told so
- [x] A phone whose key has not been confirmed in person cannot open this one's microphone
      or camera
- [x] Front camera at 640x480 and 15fps, because the picture costs battery on the phone
      that can least afford it
- [x] Foreground service of the camera and microphone types, so capture survives the screen
      going off — armed by hand while the app is visible, because from Android 14 a service
      of those types cannot be started from the background at all
- [x] The light: the recording phone's own screen, turned on over the lock screen from the
      phone that is watching — on or off, white or red, and a brightness slider that goes
      straight onto the other phone's screen. Glance is the same light with eight seconds
      on it, so a look at the room cannot be left on by accident
- [x] Raising it on a locked phone needs "display over other apps" on the phone that films —
      a granted full-screen intent alone still only produces a notification to tap
- [x] The light goes out with the call, however the call ended
- [x] The call is held against the application, not a screen: switching tabs, locking the
      phone or the system trimming an activity no longer take the microphone with them
- [x] Picture size chosen by the phone watching, per call rather than per phone, and changed
      mid-call by retuning the running camera — no renegotiation, so the picture holds
- [x] A data channel beside the media, carrying what the phone in the room knows about itself:
      battery, charger, WiFi or mobile, signal. Direct between the phones, so the backend never
      sees it and it keeps working when the WAN is down
- [x] Battery pushed from Android's own battery broadcast, on every change, rather than
      whenever somebody opens a screen
- [x] That status over the corner of the picture, the way a phone shows its own
- [x] Sound off on the watching phone, without telling the other one to stop sending
- [ ] Survives the app being swiped out of recents, which needs the call moved into the
      service process rather than merely outliving the view model

Behaviour:

- [x] Connection indicator on the observing phone: LAN / direct internet / relayed, read from
      the candidate pair ICE settled on and re-checked while the call runs
- [ ] ICE restart on network change (WiFi to mobile, AP roaming)
- [x] Automatic reconnect after a dropped connection, with backoff: the watching phone asks
      again at 2, 5, 15 then 30 seconds, for as long as somebody wants the call
- [ ] LAN-only mode: refuse to connect through the server at all
- [ ] Detect and explain why the direct path failed, rather than silently relaying

Known reasons the direct LAN path fails, all worth naming in that diagnostic:

- [ ] AP / client isolation enabled on the router
- [ ] The two phones are on different SSIDs, subnets or VLANs
- [ ] A VPN or local-loopback ad blocker on either phone captures the default route
- [ ] Local network access permission not granted (Android 16 Local Network Protection)

### Pairing

Pairing is the scan, and the scan is all of trust. No accounts:

- [x] Two phones pair by scanning a QR code. The key is read off the screen, so nothing in
      between can swap it
- [x] One scan confirms both phones: the QR carries a one-time secret the scanner signs its
      identity with, and the phone that was scanned checks it before trusting anybody
- [x] The message that says "I scanned you" travels over the WiFi, or through the server when
      there is one — so two phones can pair on mobile data
- [x] The QR carries the server address and key too, so only one phone needs them typed in
- [x] A phone's id is a digest of its key, so the server can check who is who and neither
      the server nor anyone on the WiFi can claim another phone's id
- [x] Paired phones kept on the phone and never sent anywhere, so the server cannot grant trust
- [x] Remove a paired phone from the app
- [x] Refuse to stream to a phone whose key changed, rather than only warning — a signal it
      cannot sign for is dropped, so a changed key stops the call instead of colouring a badge

### Roles
- [x] Switch this phone between recording and observing at any time
- [ ] Switch the *other* phone's mode remotely
- [ ] Swap both in one action, without re-pairing
- [x] Camera and microphone permission requested on both phones at setup, since either
      may end up recording

### Recording mode
- [x] Foreground service with camera and microphone service types
- [x] Keeps capturing with the screen off and the device locked, once armed while visible
- [ ] Lock-screen activity that controls the panel: black at brightness 0 while idle,
      white while glancing, dim red while watching — never sleeps, never wakes
- [ ] True screen off via device admin lockNow(), for LCD phones where black still glows
- [ ] Autostart on boot
- [ ] Service restarts itself after a crash or being killed
- [ ] Screen pinning, so the app is not closed by accident
- [x] Guided setup for the battery-optimisation exemption (this is the usual failure)

### Observing mode
- [x] Live audio, playing with the screen off
- [x] Keep the screen on while watching, as a remembered switch
- [x] Full-screen video, with the noise meter and status kept on the picture
- [x] The room can be watched without listening — sound off is local to the watching phone
- [x] Noise level meter: measured on the phone in the room, since WebRTC hands over what it
      records and never what it plays, and sent on the data channel several times a second
- [x] Alarm on noise, on a curve rather than one threshold and one timer: the louder the room,
      the less of it is needed — a shriek counts in two seconds, grizzling at the edge of
      audible has to keep going for half a minute, and a door closing never counts
- [x] Raised only when you could not already tell: sound off, or not looking at the picture
- [x] On the alarm channel with an alarm's audio attributes, so it is heard through a phone
      silenced for the night
- [x] The meter shows the threshold and how close the room is to tripping it, so the curve can
      be tuned by watching it
- [x] Alarm when the room stops answering: nothing on the data channel for fifteen seconds,
      when noise arrives five times a second — the failure that looks exactly like a quiet room
- [ ] Alarm takes the lock screen and turns the screen on, rather than only sounding
- [x] Alarm on the alarm channel with USAGE_ALARM, so a phone silenced for the night still
      hears it. Overriding Do Not Disturb as well needs a grant the user gives once
- [ ] Full-screen intent alert on the lock screen
- [ ] Watch the video on the lock screen without unlocking (setShowWhenLocked)
- [x] The recording phone's light shows over its own lock screen and turns its screen on
      (setShowWhenLocked, setTurnScreenOn)
- [x] Alarm keeps sounding until acknowledged: the tone is played and looped by the app rather
      than handed to a notification that plays it once, and stops on "I'm awake", on opening the
      call, or when the room answers again. Ten minutes is the cap, because an alarm in an empty
      house should not ring until the battery is flat
- [x] Ongoing notification with the current state and a stop action

### Video
- [x] Audio-only by default, as a setting: Listen starts without the camera and Watch always
      brings it, so the cheap call is the one you reach for without thinking
- [x] Turn video on and off mid-call — by starting and stopping the camera behind a track
      that is always negotiated, so it needs no renegotiation and never interrupts the audio
- [x] Remotely start video on a locked recording phone, without touching it, once it has
      been armed
- [x] Resolution and frame rate asked for by the observing phone: 320x240/10, 640x480/15 or
      1280x720/24, changeable while watching
- [ ] Adaptive bitrate, which is the automatic version of that choice
- [ ] Front / back camera switch, controlled from the observing phone

Light, because phones have no infrared and a dark room films as black:

- [x] Glance mode: screen full white at maximum brightness for eight seconds, then black
- [x] Watch mode: screen red at whatever brightness was chosen, for leaving the video
      running without waking the baby
- [x] Front camera used for screen-lit video, since screen and lens face the same way
- [x] Brightness and colour set from the observing phone, live, on a slider
- [x] Check the picture before the night: the front camera and a sound meter on the phone in
      the room, so it can be placed without a second phone
- [ ] Setup guidance that the phone belongs within about a metre of the crib
- [ ] Torch mode with the rear camera, for the best image when disruption is acceptable
- [ ] Torch dimmed via turnOnTorchWithStrengthLevel, falling back to on/off where
      FLASH_INFO_STRENGTH_MAXIMUM_LEVEL reports 1
- [ ] Torch driven by FLASH_MODE_TORCH inside the capture session while streaming, and
      by setTorchMode when idle
- [ ] Torch limited to short bursts, since the LED overheats and the phone cuts it
- [x] Light turns off again with the call, however the call ended
- [ ] Setup advises a night light as the option that actually looks good

### Alerts
- [x] Noise above a threshold, for a length that shortens as it gets louder
- [x] Adjustable: the quietest sound that counts, and how patient to be at that level
- [x] Recording phone went offline — the most important alarm, from the direct connection
      going quiet rather than a heartbeat the server has to notice
- [ ] Remote phone battery low, then critical
- [ ] Remote phone came off its charger
- [ ] This phone's own battery low, since a dead observer misses every alarm
- [ ] Observing phone lost its network

### Backend (.NET)

Optional, stateless, no database:

- [x] An optional server key, which unlocks the home relay: a second relay that may reach the
      server's own network, for when the router does no NAT loopback
- [x] Phones sign in with their identity key: a signature over the current time, checked
      against the key they send, which gives an id nobody else can claim
- [x] SignalR hub that relays signals to one phone by id and says whether it was connected
- [x] Presence: a phone asks to watch its paired phones and hears when they come and go
- [x] TURN credential endpoint issuing short-lived HMAC credentials, with no accounts to
      provision on the relay
- [x] coturn itself, in its own project, with every internal range refused as a peer
- [ ] FCM push to wake an observing phone whose connection dropped
- [x] No media stored, no media decrypted — the backend carries signalling only, and the
      relay forwards ciphertext it holds no key for

---

## After the MVP

### Interaction
- [ ] Push-to-talk from the observing phone to the recording phone's speaker
- [ ] Play a lullaby or white noise on the recording phone, started remotely
- [ ] Remote volume control for playback on the recording phone
- [ ] Microphone gain / sensitivity setting

### Multiple devices
- [ ] Several observing phones listening to one recording phone at the same time
- [ ] Several recording phones, switchable from one observing phone
- [ ] Alarm acknowledged on one observing phone silences the others

### Convenience
- [ ] Automatically enable video when noise is detected
- [ ] Snapshot from the current video stream
- [ ] Noise timeline for the night, to see how often the baby woke
- [ ] Quick settings tile to start and stop the recording phone
- [ ] Home screen widget showing the connection state

### Robustness
- [ ] Bandwidth and relay usage shown, so relayed video is never a surprise
- [ ] Data usage cap on mobile networks
- [ ] Diagnostics screen: ICE state, selected candidate pair, RTT, packet loss

---

## Explicitly out of scope
- Cloud recording or storage of any media
- Accounts, sign-up, or anything beyond pairing two devices
- Play Store distribution — the app is sideloaded
