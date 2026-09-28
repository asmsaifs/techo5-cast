# techo5-cast

Cast a phone's screen and audio straight to a [TECHO5](https://github.com/HuskerMinion/techo5) Echo
Show, with no server between them.

- `wire/`: the protocol, in Go: the encrypted connection and the messages. [docs/protocol.md](docs/protocol.md)
  is the specification.
- `cmd/castsend`: sends a video file to a device the way the phone will. It is the bench for the device.
- The device side is `echod/internal/feature/cast` in the techo5 repository (its `secure.go` and
  `proto.go` are copies of `wire/`, and have to match).
- The Android app (MediaProjection for the screen, AudioPlaybackCapture for the sound) is not started.

## Try it

Needs `ffmpeg` (and `ffprobe`); `yt-dlp` too for YouTube and other web pages.

```sh
go build -o bin/castsend ./cmd/castsend
export CASTKEY=the-pairing-key            # the device's Cast key (cast_key action in Home Assistant)

bin/castsend -list                        # devices found over mDNS
bin/castsend -i clip.mp4                  # picks the device it finds
bin/castsend -i https://example.com/film.mp4 -scale 2 -fps 30
bin/castsend -i 'https://www.youtube.com/watch?v=…' -scale 2     # needs yt-dlp
bin/castsend -addr 192.168.1.50:8940 -ss 1:30 -t 60 -i clip.mp4
```

Ctrl-C ends the cast, and so does a swipe in from the left edge of the device's screen. On this
hardware `-scale 2` is the setting to use for real video (see [docs/protocol.md](docs/protocol.md) for
the numbers).

On the device, turn on the **Cast** switch in Home Assistant and set the key with the `cast_key`
action. The device log (`/data/techo5-linux/techo5.log`) prints `cast decoded_fps=… painted_fps=…` and
why any frames were dropped, every five seconds.

## Status

- [x] Protocol, encryption, timing, dropping of late frames (device receiver, tested)
- [x] `castsend` test sender
- [x] Measured on a real Echo Show: 30 fps at half scale with real video, audio clean
- [ ] A/V drift over a long cast (the card's clock against the phone's)
- [x] Device: cast page on the screen, audio to the speaker, mDNS advert, `Cast` switch and `cast_key` action (techo5 branch `feature/cast`; compiled, not yet run on a Show)
- [ ] Device: clock-drift correction for long casts, an on-screen consent prompt, a row in the settings sheet
- [ ] Android app
