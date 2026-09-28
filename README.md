# techno5-cast

Cast a phone's screen and audio straight to a [TECHO5](https://github.com/HuskerMinion/techo5) Echo
Show, with no server between them.

- `wire/`: the protocol, in Go: the encrypted connection and the messages. [docs/protocol.md](docs/protocol.md)
  is the specification.
- `cmd/castsend`: sends a video file to a device the way the phone will. It is the bench for the device.
- The device side is `echod/internal/feature/cast` in the techo5 repository (its `secure.go` and
  `proto.go` are copies of `wire/`, and have to match).
- The Android app (MediaProjection for the screen, AudioPlaybackCapture for the sound) is not started.

## Try it

Needs `ffmpeg`. On the machine with the Go toolchain:

```sh
go build -o bin/castsend ./cmd/castsend

# a stand-in for the device, on this machine:
cd ../techo5/echod && CAST_BENCH=:8940 CAST_KEY=pairing-key \
  go test -count=1 -run TestBench -v ./internal/feature/cast

# in another terminal:
bin/castsend -addr 127.0.0.1:8940 -key pairing-key -i clip.mp4
```

On the device, build the bench for it (`GOOS=linux GOARCH=arm GOARM=7 go test -c -o castbench
./internal/feature/cast` in `echod`), copy it over, run it there, and point `castsend` at the device.
It shows nothing on the screen yet; it reports the frame rate the device sustains.

## Status

- [x] Protocol, encryption, timing, dropping of late frames (device receiver, tested)
- [x] `castsend` test sender
- [ ] Measure on a real Echo Show (frames per second, CPU next to the wake word, A/V drift)
- [ ] Device: cast page on the screen, audio to the speaker, mDNS advert, on-screen consent
- [ ] Android app
