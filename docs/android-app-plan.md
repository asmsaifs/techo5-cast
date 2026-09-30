# Android app plan

The phone half of techo5-cast: an app that sends a video, or the whole screen, to a TECHO5 Echo Show
over the protocol in [protocol.md](protocol.md). This plan is written before any app code. The device
side and `castsend` already work; what is left is the phone.

> **Status (2026-09-30):** M0 to M6 are built and were run on a Nothing Phone (2) and an Echo Show 5.
> M7 is the release machinery: CI, signed APKs from a tag, store text, licences and the README
> ([releasing.md](releasing.md)). Not yet done: the first tag, and the Show's receiver in a signed
> TECHO5 release. F-Droid's main repository will not take yt-dlp's prebuilt runtime (section 9).

## 1. Goal

From a phone, in a few taps:

1. **Share a YouTube video** (or a link from any other video app or site) to the app, pick the Show, and
   it plays there, picture and sound.
2. **Share or open a video file** (Gallery, Files, Downloads, a messaging app) the same way.
3. **Control it** from the phone: pause, seek, stop, from the notification and the app.
4. **Mirror the phone's screen and sound** for apps that can't hand over a link.

The Show is the screen and the speaker. The phone does the work of getting the video, decoding it and
sending small JPEG frames and PCM audio. Nothing runs in between: no server.

### Not goals

- Appearing as a target in YouTube's own Cast button. That is Google's Cast protocol and needs a
  licensed receiver; the Show can't be one. The share sheet is the way in.
- DRM video (Netflix, Prime, Disney+, most paid content). Links to it can't be played by the app, and
  mirroring it gives a black picture. See section 3.
- Casting to more than one device at once, at first.
- Publishing on Google Play. Section 9 says why.

## 2. Decisions made

| | Decision |
|---|---|
| Test phone | Nothing Phone (2), Android 16 |
| SDK levels | `minSdk` 29 (needed for audio capture in mirror mode), `targetSdk` 36 |
| Extractor | youtubedl-android (yt-dlp on the phone), behind an `Extractor` interface |
| Licence | App GPL-3.0; `wire/`, the protocol and `castsend` stay MIT |
| Distribution | GitHub Releases (signed APK) and F-Droid; no Play Store |
| Noise on Android | Pure Kotlin/Java (noise-java), interop-tested against the Go code; `gomobile` only as a fallback |
| First release | Files, links **and** mirroring |
| Name | **TECHO5 Cast** (section 16) |

Why pure Kotlin for Noise: the protocol layer is small (about 300 lines in Go), noise-java implements
`NNpsk0` with X25519, ChaChaPoly and SHA-256, and staying out of `gomobile` means no embedded Go
runtime, no second toolchain in the Android build, and a smaller APK. The risk it adds is a subtle
mismatch with the Go side, which the interop test in section 6 exists to catch on day one.

### Android 16 notes

- **Foreground services** need declared types (`mediaPlayback`, `mediaProjection`) and can't be started
  from the background; the app starts them from a visible activity or a user action (a share, a tile).
- **Screen capture** since Android 14: consent every session, and the user may share a **single app's
  window** instead of the whole screen. Treat that as normal, not an error.
- **Local network access**: Android 16 begins putting local-network use behind a permission (opt-in
  now, expected to be enforced later). Declare and request it if it exists on the test phone; check
  in the M0 spike rather than assume.
- **Notifications** need runtime permission; edge-to-edge is the default for the UI.

## 3. Flows

### 3.1 Share from YouTube (and most other apps)

```
YouTube -> Share -> "<App name>" -> (device picker, skipped if one Show or the last one is remembered)
        -> toast "Casting to Kitchen Show" -> the share sheet closes, YouTube is still in front
        -> notification: title, pause / stop
```

The app receives `ACTION_SEND` with `text/plain`. The text is a URL, sometimes with words around it
("Watch this: https://youtu.be/..."), so the app finds the first URL in it. It never shows a full
screen for this: a transparent activity does the picking and starts the service, then finishes.

### 3.2 Share or open a file

`ACTION_SEND` and `ACTION_VIEW` with `video/*` and `audio/*` give a `content://` URI. The app reads it
through the content resolver, which needs no storage permission. The same flow follows.

Inside the app, **Cast a file** opens the system picker (`ACTION_OPEN_DOCUMENT`), and **Paste a link**
takes any URL.

### 3.3 Open the app

Devices found nearby (with their state: ready, casting), a **Now casting** card with the controls,
and a way to add a key for a new device. Settings for quality.

### 3.4 Mirror

A quick-settings tile and a button in the app. The system shows its own screen-capture consent, then
the screen (or the one app the user picks) and the sound of other apps go to the Show.

### What can and can't be cast

| Source | How | Works? |
|---|---|---|
| YouTube link | Extractor turns it into a picture stream and a sound stream; the app plays both | Yes, up to 720p is plenty for a 960x480 screen |
| Link to another site | Same, if the extractor supports the site (hundreds do) | Usually |
| Direct video URL (`.mp4`, `.m3u8`...) | Played as it is | Yes |
| Video file on the phone | Played from the content URI | Yes |
| Live stream | Extractor gives an HLS URL | Should work; untested |
| Age-restricted, private, members-only | Needs the user's login cookies | No, at first |
| DRM (Netflix, Prime, Disney+, Widevine) | Not playable off-app | **No.** Links give nothing to play, and mirroring gives black (the apps mark their windows secure) |
| Anything else on the phone's screen | Mirror mode | Yes, except secure windows; some apps also opt out of audio capture |

Say this plainly in the app: when a link can't be played, show "this video can't be cast: it is
protected or needs a sign-in", not a stack trace.

## 4. Architecture

```
  Android share / view / app UI / quick-settings tile
             |
        Intake activity -- resolve URL (extractor) -- pick device
             |
        CastService (foreground) ---------------------------------+
             |                                                    |
   +---------+-----------+                              MediaSession + notification
   |  Sources            |
   |  Media3 ExoPlayer   |  (links, files)
   |  MediaProjection    |  (mirror)
   +---------+-----------+
             |  video -> GL frame grabber -> JPEG
             |  audio -> PCM tap / playback capture (48 kHz stereo)
             |
        Timeline (one clock for both)
             |
        Protocol client (Noise NNpsk0)
             |  TCP :8940
         the Show
```

Modules (Gradle), so the risky parts can be built and tested alone:

| Module | Holds |
|---|---|
| `protocol` | Noise handshake, framing, messages, stamps. Pure Kotlin/JVM, no Android; tested against the Go device code |
| `engine` | ExoPlayer setup, the frame grabber, the audio tap, `Timeline`, send loop, stall handling, mirror capture |
| `discovery` | mDNS browse (`NsdManager`), saved devices, keys |
| `extract` | URL -> playable streams; the only module that knows about `yt-dlp` |
| `app` | Activities, service, notification, tile, UI (Jetpack Compose), settings |

Language and stack: Kotlin, Jetpack Compose (Material 3), Media3 (ExoPlayer, MediaSession), coroutines
and Flow, Gradle with a version catalog. Repo layout: `android/` inside the techo5-cast repository.

## 5. The engine

This is where the difficulty is. The device only understands JPEG frames and PCM audio with stamps on
the phone's clock; the phone has to turn any video into that, in real time, on a phone that may also be
doing other things.

### 5.1 Playing

Use **Media3 ExoPlayer** for everything that is a link or a file. It already does adaptive streams,
seeking, buffering, containers and codecs, and it gives the phone-side controls for free. Two streams
from an extractor (video-only and audio-only, which is what YouTube serves) go in as a
`MergingMediaSource`.

The player is never shown on the phone. Its picture goes to our surface, its sound to our tap.

### 5.2 Video: frames out

Options, in the order to try them (**spike first**, section 14):

1. **`SurfaceTexture` + OpenGL.** The player renders to a `SurfaceTexture`; a small GL pass draws it
   letterboxed into a 480x240 (or 960x480) framebuffer; `glReadPixels` reads it back to a `Bitmap`;
   `Bitmap.compress(JPEG)` encodes it. Scaling happens on the GPU, so the CPU only sees the small
   frame. Most portable and the likeliest to be right.
2. **`ImageReader`** as the player's surface, at the small size. Less code, but not every phone scales
   codec output to the reader's size, and the YUV -> JPEG step is slower.

Frame rate: capture at the source's rate but send at most the chosen rate (default 30, 15 on request).
Skip a frame if the encoder is still busy: never queue.

JPEG quality about 70-85. Half-size frames (protocol `scale: 2`) are the default: the device decodes a
quarter of the pixels, and measured on the Show that is what makes 30 fps possible.

### 5.3 Audio: PCM out

An `AudioProcessor` in ExoPlayer's `DefaultAudioSink` chain taps the decoded sound after it has been
converted to **48 kHz stereo signed 16-bit** (the speaker's format; the device refuses anything else).
Use `ChannelMappingAudioProcessor` and `SonicAudioProcessor` with the output rate set to 48000, then
our tap, which copies 20 ms pieces out. The phone's own speaker is muted with `player.volume = 0`
(the tap sits before the volume), so the phone is silent and the Show plays.

### 5.4 One clock: the `Timeline`

Everything on the wire is stamped on the phone's monotonic clock (`SystemClock.elapsedRealtimeNanos`
in microseconds), and a stamp means *when to show this*, not *where in the clip it is*. `castsend`
learned this the hard way: see "Stalls" in [protocol.md](protocol.md).

- **Video** has a real answer: Media3's `VideoFrameMetadataListener.onVideoFrameAboutToBeRendered`
  gives each frame's presentation time and the `System.nanoTime` it will be released. That is the
  stamp, directly.
- **Audio** has no such callback. Count samples since the last flush and map them to the phone clock
  with the same anchor the video gives (media time -> clock time); re-anchor on every seek, pause,
  rebuffer and discontinuity (`onPositionDiscontinuity`, `onPlaybackStateChanged`).
- **Pause**: stop sending; the device keeps the last frame and plays out what it has buffered.
- **Seek**: flush both streams; stamps jump to the new anchor. The device needs nothing special: it
  buffers 250 ms and every message is stamped in phone time.
- **Rebuffering / stalls**: if either stream falls more than 300 ms behind real time, shift both
  together (the same `timeline` as `castsend`) and pace sending so nothing runs ahead of what the
  device buffers (`latency_ms`).

Play, pause and seek all live on the phone; the device just shows what it is sent.

### 5.5 Sending

One coroutine per stream feeds a shared `Channel` with a small bound; one writer sends. Rules:

- **Video is droppable, audio is not.** If the socket is slow, drop old frames; keep every audio
  chunk (the buffer is a few seconds at most, then re-sync).
- **Backpressure** from a slow Wi-Fi link shows as `write` blocking; measure it and lower the
  frame rate one step at a time (30 -> 24 -> 15), raise it again after a quiet minute.
- The connection is TCP with Noise on top; `TCP_NODELAY` on.
- On disconnect: try to reconnect for about 10 s at the same position before giving up and telling the
  user.

### 5.6 Mirror mode

The same pipeline with different sources: a `MediaProjection` `VirtualDisplay` in place of the player's
surface (GL grab as in 5.2), and `AudioPlaybackCaptureConfiguration` feeding an `AudioRecord` in
place of the audio tap. The timeline is simpler: stamp by capture time. Needs a foreground service of
type `mediaProjection`. The virtual display is created at the Show's aspect ratio and the phone's
picture is letterboxed into it (a portrait phone on a 2:1 screen leaves bars; offer a rotate hint).
The system's consent appears every session and the user may pick one app instead of the screen.

## 6. The protocol client

Kotlin port of `wire/`; the spec is [protocol.md](protocol.md), the reference implementation
`wire/secure.go`.

- **Noise `NNpsk0`**, `Noise_NNpsk0_25519_ChaChaPoly_SHA256`, prologue `techo5-cast/1`, PSK = SHA-256 of
  `"techo5-cast psk:" + key`. Use [noise-java](https://github.com/rweather/noise-java) (supports
  `NNpsk0`); check its licence and Android compatibility, and write the handful of primitives
  ourselves if it does not fit.
- **Records**: 4-byte length + ciphertext, at most 60000 bytes of plaintext each; messages inside
  are 4-byte length + kind + payload.
- **Interop test first**: a Go test server built from `wire.Accept` (it already exists) that the
  Kotlin client connects to in a JVM unit test (start the Go server as a process), and the reverse.
  A mismatch in PSK placement or the prologue shows up here, not at the Show.
- Fallback if that fights back: `gomobile bind` the Go `wire` package into an AAR, so both ends
  are literally the same code. It costs a few MB and a Go runtime.

Protocol additions worth making before release (backwards compatible):

- **`KindStats` (device -> phone, about 1/s):** frames shown, frames dropped and why, audio late. Lets the
  phone adapt frame rate and quality instead of guessing, and shows "smooth / struggling" in the UI.
- **`hello.title`:** what is playing, so the Show can say "Casting: ..." on screen.
- **`KindFlush`** (phone -> device), if seeks turn out to leave stale frames or audio in the device's
  buffer.

## 7. Discovery, pairing, keys

- **Discovery**: `NsdManager` browsing `_techo5cast._tcp`. Holds a `MulticastLock` while browsing.
  Shows name, address and whether it answered. **Add by address** for networks where mDNS is blocked.
- **The key**: the device's Cast key is set in Home Assistant (`cast_key`). The app takes it typed,
  pasted or, better, **scanned from a QR code** (the Show could display one; phase 6). It is stored
  per device, encrypted with an Android Keystore key (`EncryptedFile`/`EncryptedSharedPreferences`).
- **Trust**: the key *is* the authorisation, so nothing else in the app needs to be a secret. A wrong
  key fails the handshake and the app says "the key doesn't match".

## 8. Android integration

### 8.1 Manifest

```
Intake activity (transparent, excluded from recents, no history)
  ACTION_SEND     text/plain       -> a URL, maybe with words around it
  ACTION_SEND     video/*, audio/* -> a content:// stream
  ACTION_VIEW     video/*, audio/* (content, file, http, https) -> "open with"
  ACTION_SEND_MULTIPLE             -> the first video only
Main activity        -> launcher
CastService          -> foregroundServiceType = mediaPlayback | mediaProjection
Quick-settings tile  -> starts mirror mode
```

Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS` (asked
when first needed), `WAKE_LOCK`, `RECORD_AUDIO` (needed by playback capture in mirror mode, asked only
then), and the local-network permission if Android 16 requires it (section 2). No storage permission:
files come as content URIs.

### 8.2 The service

A foreground service owns the player or the projection, the connection and a `MediaSession`, so casting
continues with the screen off and the app closed, and the notification (and Bluetooth headset, car and
lock-screen controls) work. Hold a partial wake lock and a Wi-Fi lock while casting, and release both
on stop. A second share while casting to the same device just switches the video; to a different
device it asks first.

### 8.3 Battery and heat

Screen off, hardware video decode, GPU scaling, JPEG at a quarter of the phone's resolution: it should
be far lighter than mirroring. Measure it (section 14) and expose a **battery saver** option: 15 fps,
lower JPEG quality.

## 9. Getting the video: the extractor

A share from YouTube is a *page link*. Something on the phone has to turn it into stream URLs, since no
server is involved. **youtubedl-android** runs yt-dlp on the phone (all 1000+ sites yt-dlp supports),
can update yt-dlp inside the app without a new release, and is about 25-40 MB larger. It sits behind an
`Extractor` interface in `extract/`, so NewPipeExtractor (smaller, YouTube-focused, needs an app release
whenever YouTube changes) could replace it.

Format choice as `castsend` does it: `bv*[height<=720]+ba/b[height<=720]/b`, prefer H.264 for cheap
hardware decode, and give the player both URLs when there are two.

Practical points:

- Extraction takes 1-4 s: show "Getting the video..." straight away.
- URLs expire (hours): re-extract on a playback error or a seek after a long pause.
- Some sites need a `User-Agent` or `Referer` header; the extractor returns them and the player's
  `DataSource` must set them.
- **Licence**: youtubedl-android is GPL-3.0, so the app is GPL-3.0. The protocol, `wire/` and
  `castsend` stay MIT.
- **Distribution**: an app that fetches YouTube streams outside YouTube's own player breaks YouTube's
  terms and Google Play's policies, so **Play Store is out**. GitHub Releases (signed APK) and F-Droid.
  Say so in the README.

## 10. Screens

1. **Home**: nearby devices (name, address, state); "Now casting" card (title, device, play/pause,
   seek bar, stop, quality indicator from `KindStats`); "Cast a file", "Paste a link", "Mirror screen".
2. **Pick a device** (bottom sheet from the share flow): devices found, last used first, a remembered
   choice; "Only ask when there is more than one".
3. **Add a device**: found ones list; key entry; QR scan; "Test connection".
4. **Settings**: frame rate (30 / 24 / 15), size (half / full), JPEG quality, max video height,
   battery saver, keep-awake, diagnostics (copy log), licences.
5. **Errors as sentences**: can't reach the Show (is Cast on?), wrong key, Show is busy with another
   cast, can't play this video (protected / needs sign-in / network), the Show ended the cast.

Jetpack Compose with Material 3; dark theme; one activity.

## 11. Device-side work (in the techo5 repository)

The phone will need these from the Show; none blocks the first milestones.

- **Consent** before a cast starts: "Pixel is casting. Allow?" with Accept / Decline on the Show's
  screen (needs a page and a touch handler like the dashboard's). Today the key is the only gate.
- **`KindStats`** and the title on screen ("Casting: ...", shown briefly).
- A row in the on-device **settings sheet** for Cast on/off and a way to show the pairing QR code.
- **Clock-drift correction** in the speaker source (the card's clock against the phone's, about
  12 ms a minute): matters for long videos.
- Make the running build permanent: today it is a bind mount over the read-only root and disappears on
  reboot. The real route is a slot update via the project's release process.

## 12. Repository and build

```
techo5-cast/
  wire/ cmd/castsend/ docs/      (existing: Go)
  android/
    protocol/  engine/  discovery/  extract/  app/
    gradle/libs.versions.toml
  interop/                        (Go test server the JVM tests start)
```

CI (later): Go tests, Gradle unit tests, lint, a signed release APK on a tag.

## 13. Testing

- **Unit** (JVM): Noise interop with the Go server, framing, `Timeline` (stalls, seeks, pauses,
  audio/video shift together), URL extraction from share text, key storage.
- **Golden test** for the stamp mapping: feed a recorded sequence of player callbacks, assert the
  stamps.
- **Instrumented**: intent filters (a share of each kind starts the right thing), permissions, the
  service lifecycle.
- **On the Show**: the device log (`cast decoded_fps=... painted_fps=...` and the drop counters) is the
  ground truth. Every milestone's exit criteria are measured there.
- **Manual matrix**: the Nothing Phone (2) first, then any other phone available; Wi-Fi 2.4 GHz and
  5 GHz, YouTube video / live / Shorts / a playlist link, a file over 1 GB, a `.mkv`, screen off for 10
  minutes, phone call interruptions, leaving Wi-Fi range.

## 14. Milestones

Effort assumes one developer; the first milestone is a spike because it holds the risk. The first
release includes mirroring (M5).

| # | What | Exit criteria | Effort |
|---|---|---|---|
| **M0** | **Spike.** No UI beyond a button. (a) Noise handshake from Kotlin against the Go device; (b) ExoPlayer -> GL -> JPEG frames from a local file; (c) audio tap giving 48 kHz stereo PCM; send both to the Show; note the Android 16 local-network permission question | A file plays on the Show from the phone, 30 fps half-size, sound in step; phone CPU/temperature noted; the GL-or-ImageReader question answered | 3-5 days |
| **M1** | **Cast a file.** Discovery, add device with key, "Cast a file", service and notification (play/pause/stop) | A 10-minute video plays start to finish, screen off, no drift you can hear | 4-6 days |
| **M2** | **Share intake + links.** Intent filters, transparent intake activity, device picker, extractor, direct URLs | Share from YouTube app (and another site) starts casting in 5 s or less, from the share sheet | 4-6 days |
| **M3** | **Robustness.** `Timeline` with stalls, seeks, pause; reconnect; adaptive rate from `KindStats`; error messages | A 1-hour YouTube video plays through, surviving a Wi-Fi drop; seeking works; the Show's drop counters stay near zero | 5-7 days |
| **M4** | **Polish.** Settings, saved devices, direct-share targets, battery saver, onboarding, logs, licences | Someone else can install and use it from the README | 4-5 days |
| **M5** | **Mirror mode.** MediaProjection, playback capture, tile | Whole-screen (or one app) mirroring with sound at 15-30 fps | 4-6 days |
| **M6** | **Device side.** Consent page, stats, title, settings row, QR, drift correction, a real (slot) install | Casting is safe by default; long casts stay in sync | 5-8 days |
| **M7** | **Release.** Signed APKs, F-Droid metadata, docs, ToS note | A tagged release | 2-3 days |

M0 through M3 is the useful app; roughly three to four weeks of focused work. All of it, with mirroring
and the device side, is closer to six.

## 15. Risks

| Risk | Why it matters | What to do |
|---|---|---|
| GL frame grab is slow or awkward on some phones | Sets the frame rate and battery | M0 spike first; keep `ImageReader` as the fallback |
| Audio/video sync from ExoPlayer callbacks | Video has release times, audio does not | Use the video mapping for both; test with a clapper clip; fall back to a null sink with its own clock |
| YouTube changes break extraction | Casting stops working | yt-dlp updates in place; an `Extractor` interface; show the extractor's version in settings |
| Noise implementation mismatch | Handshake fails on the wire | Interop tests from day one; `gomobile` as the fallback |
| Wi-Fi is worse than the lab's | Stutter, drops | `KindStats`-driven adaptation; 15 fps mode; clear "weak signal" message |
| DRM and protected video | Users expect it to work | Say what can't work, up front, in the app |
| Android 16 background and capture rules | Service or capture refused | Start from a visible action; declare service types; test on the Phone (2) at M0 |
| Phone thermal throttling on long casts | Frame rate falls over time | Lower rate step after temperature or CPU load rises; measure in M0/M3 |
| Doze and vendor background limits | Service killed with the screen off | Foreground service + wake lock + battery-optimisation guidance; test on a few brands |
| A stranger on the network knows the port | Anyone can try keys | Key is 8+ chars; add handshake rate limiting and the consent prompt (M6) |

## 16. The name

**Decided: TECHO5 Cast.** Package id `io.github.<you>.techo5cast` (the account name is still to be
filled in). The options that were considered, most to least clear:

| Name | Why |
|---|---|
| **TECHO5 Cast** | Says what it is and who it is for; matches the project. Package `io.github.<you>.techo5cast` |
| **Show Beam** | Short, friendly; "beam this to the Show" |
| **CastLocal** | Echoes EchoLocal, the project TECHO5 is built on; says "no cloud" |
| **Techo Toss** | Playful: toss a video to the Show |

Avoid "Echo" on its own (Amazon's trademark) and "Chromecast" or "Cast" alone (Google's).

## 17. What comes first

Not the app: the **M0 spike**, as a throwaway project, with the two riskiest parts side by side: the
Kotlin Noise handshake against the Show, and ExoPlayer -> GL -> JPEG with the audio tap, on the Nothing
Phone (2). If both work, the rest is engineering; if the frame grab is slow or the sync is hard, better
to know in a week than in a month.
