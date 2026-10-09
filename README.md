# TECHO5 Cast

Cast a video, or your phone's whole screen, straight to a [TECHO5](https://github.com/asmsaifs/techo5)
Echo Show, with no server between them.

In YouTube (or any video app) tap **Share → TECHO5 Cast**, and it plays on the Show, picture and sound.
Share a video file the same way, or **Mirror screen** for an app that can't hand over a link. Pause, seek and
stop from the notification. The phone does the work of getting and decoding the video and sends small JPEG
frames and audio over your own Wi-Fi, encrypted; the Show just displays them.

## Install the app

1. On the Show, open **Settings → Connections**, turn **Cast from a phone** on, and tap **Show** on
   **Pairing code**. (The Show needs a TECHO5 build with the cast receiver; see
   [docs/releasing.md](docs/releasing.md#the-show-side), and the Show's side of this in
   [techo5 docs/cast.md](https://github.com/asmsaifs/techo5/blob/main/docs/cast.md).)
2. On the phone, download the APK for your phone from the
   [latest release](https://github.com/asmsaifs/techo5-cast/releases/latest): `arm64-v8a` for nearly every
   phone made since 2017, or the `universal` one if unsure. Open it and allow installing from that source
   when Android asks. Check the download against `SHA256SUMS` if you like.
3. In the app, **Add a Show → Scan the code**, then **Check connection** and **Save**.
4. In YouTube, tap **Share → TECHO5 Cast**. The Show asks you to **Accept**; after that, it plays.

Needs Android 10 or later (Android 14+ for the best mirror experience).

### What works, and what doesn't

| | |
|---|---|
| YouTube, and links from other sites yt-dlp supports | Yes, up to 720p |
| Direct video links, video files on the phone | Yes |
| Mirror the screen or one app, with sound | Yes; some apps block sound capture |
| DRM video (Netflix, Prime Video, Disney+…) | **No.** Links give nothing to play; mirroring shows black |
| Age-restricted, private, members-only videos | No (they need a sign-in) |
| YouTube "not a bot" block on your connection | Yes, once you sign in under Settings → YouTube (only the cookies are kept) |
| YouTube's own Cast button | No. That is Google's protocol, and a Show can't be a receiver. Use Share. |

While mirroring, the phone's screen stays on (Android ends a screen capture when the lock screen appears).
Pressing the power button ends it.

## A note on YouTube's terms

The app turns a YouTube link into video streams with [yt-dlp](https://github.com/yt-dlp/yt-dlp) running on
your phone, and plays them itself rather than in YouTube's own player. YouTube's terms of service do not
allow that, and neither do Google Play's policies, **which is why this app is not on Google Play** and is
released as a signed APK here instead. It is meant for watching videos you could watch anyway, on your own
screen. You are responsible for how you use it.

## Privacy

No accounts, no analytics, no advertising, no crash reporting. The phone talks to your Show (on your network,
encrypted with the key from the pairing code) and to the sites of the videos you choose to cast, and
downloads yt-dlp updates from GitHub. The Show's key is stored encrypted on the phone. The pairing code holds
that key, so the Show draws it only when asked and puts it away after two minutes or a touch.

## What's in this repository

- `android/`: the app ([licence: GPL-3.0](android/LICENSE)). Kotlin, Jetpack Compose, Media3; modules
  `protocol` (Noise handshake and messages, plain JVM), `engine` (ExoPlayer → GPU → JPEG, audio tap,
  mirror, timeline, send loop), `discovery`, `extract` (yt-dlp), `app`.
  [docs/android-app-plan.md](docs/android-app-plan.md) is the design.
- `wire/`: the protocol, in Go: the encrypted connection and the messages.
  [docs/protocol.md](docs/protocol.md) is the specification.
- `cmd/castsend`: sends a video file to a device the way the phone does; the bench for the device.
- The device side is `echod/internal/feature/cast` in the techo5 repository (its `secure.go` and `proto.go`
  are copies of `wire/`, and have to match).

The protocol, `wire/`, `interop/` and `castsend` are [MIT](LICENSE); the app is GPL-3.0 because it includes
GPL software (youtubedl-android).

## Build the app

Android Studio's JDK 17 (or any JDK 17), the Android SDK (platform 36), and Go (the protocol tests run the Go
side):

```sh
cd android
./gradlew :protocol:test :engine:testDebugUnitTest lintDebug   # tests and lint
./gradlew :app:assembleDebug                                   # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Releases are built and signed by CI from a tag; [docs/releasing.md](docs/releasing.md) has that, a local
signed build, and where the app can be distributed.

## castsend, the bench sender

Needs `ffmpeg` (and `ffprobe`); `yt-dlp` too for YouTube and other web pages.

```sh
go build -o bin/castsend ./cmd/castsend
export CASTKEY=the-pairing-key            # the device's Cast key: read it off the pairing code page on the device

bin/castsend -list                        # devices found over mDNS
bin/castsend -i clip.mp4                  # picks the device it finds
bin/castsend -i https://example.com/film.mp4 -scale 2 -fps 30
bin/castsend -i 'https://www.youtube.com/watch?v=…' -scale 2     # needs yt-dlp
bin/castsend -addr 192.168.1.50:8940 -ss 1:30 -t 60 -i clip.mp4
```

Ctrl-C ends the cast, and so does a swipe in from the left edge of the device's screen. On this hardware
`-scale 2` is the setting to use for real video (see [docs/protocol.md](docs/protocol.md) for the numbers).

On the device, turn Cast on (the **Cast** switch in Home Assistant, or Settings → Connections). It makes its
own random key; **Pairing code** shows it as a QR code for the phone app, and in letters for `castsend`. The
`cast_key` action is only for choosing a key yourself, or, with no key, making a new one. The device log
(`/data/techo5-linux/techo5.log`) prints `cast decoded_fps=… painted_fps=…` and why any frames were dropped,
every five seconds.

## Status

- [x] Protocol, encryption, timing, dropping of late frames, stall handling (tested)
- [x] Device: cast page, audio to the speaker, mDNS, on-screen Accept/Decline, stats and title, clock-drift
  correction, settings rows, pairing QR, its own key
- [x] Android app: files, links, share sheet, mirror, notification and tile, reconnect, adaptive frame rate,
  pairing by QR
- [x] Measured on a real Echo Show 5: 30 fps at half scale with real video, audio clean
- [x] The Show's cast receiver in a signed TECHO5 release, installed over the air
- [x] First tagged app release ([v0.1.0](https://github.com/asmsaifs/techo5-cast/releases/tag/v0.1.0), signed APKs)
- [ ] F-Droid: not in the main repository (its prebuilt yt-dlp runtime is not accepted); see [docs/releasing.md](docs/releasing.md)
