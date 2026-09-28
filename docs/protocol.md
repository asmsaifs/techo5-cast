# techno5-cast protocol, version 1

A phone sends its screen and audio directly to a TECHO5 device. No server in between.

## Finding the device

The device advertises `_techno5cast._tcp` over mDNS (planned; the port is the device's setting). The
sender may also be given `host:port`.

## The connection

TCP, then Noise `NNpsk0` (`Noise_NNpsk0_25519_ChaChaPoly_SHA256`), prologue `techno5-cast/1`. The
pre-shared key is the SHA-256 of `"techno5-cast psk:" + pairing key`. A wrong key fails the handshake;
the key never crosses the network.

Every handshake message and every record after it is a 4-byte big-endian length, then that many bytes.
A record carries at most 60000 bytes of the stream; larger messages span records. Handshake messages are
at most 256 bytes.

The device takes one cast at a time and closes a second connection before the handshake.

## Messages

Inside the encrypted stream: a 4-byte big-endian length, a kind byte, the payload. At most 4 MiB.

| Kind | Name | Direction | Payload |
|---|---|---|---|
| 0x01 | hello | phone → device | JSON `{name, video, audio, rate, channels, scale}`; the first message, once |
| 0x10 | welcome | device → phone | JSON `{ok, reason, w, h, rate, channels, latency_ms}` |
| 0x02 | video | phone → device | 8-byte stamp (µs), then a JPEG of the whole frame |
| 0x03 | audio | phone → device | 8-byte stamp (µs), then interleaved S16LE PCM, 48000 Hz, 2 channels |
| 0x04 | clock | phone → device | 8-byte stamp: the phone's clock now, about once a second |
| 0x05 | bye | phone → device | empty |

A refused hello is answered with `ok:false` and a `reason` for the person to read ("in a call",
"declined"), and the device closes.

## Scale

`scale` in the hello is 1 (frames are screen size, the default) or 2 (frames are half the screen's
width and height, and the device draws each pixel four times). Decoding a JPEG is what limits a device,
and at 2 it does a quarter of the work for a softer picture. A device refuses a frame larger than the
screen divided by the scale.

## Time

Stamps are on the phone's monotonic clock, in microseconds. The device never needs the two clocks to
agree, only to know their difference, which it takes from the clock messages: arrival time minus stamp,
the smallest of the last ten (the network only adds delay).

A frame or chunk is presented at `stamp + offset + latency_ms`. So the phone stamps a frame with the
moment it was captured, and sound and picture stay together however the network jitters.

Before the first clock message nothing can be placed and media is dropped, so **the phone sends a clock
message first**.

## What the device drops

- A frame more than 100 ms past its time, and older frames when a newer one is waiting: the picture
  stays on the present.
- A frame larger than the screen (`w`×`h` in the welcome).
- Audio that is not whole 4-byte frames; anything stamped more than 2 s ahead of now.
- Media the hello did not announce.

Frames and audio may be sent up to `latency_ms` ahead of real time and no further. Ten seconds with no
message at all ends the cast.

## Numbers so far

Measured on an Echo Show 5 (2nd gen) over Wi-Fi with the TECHO5 daemon and wake word running, casting
Big Buck Bunny (720p) with `castsend`, JPEG quality 6:

| Sent | Frames the device showed | Bandwidth |
|---|---|---|
| scale 1, 15 fps | about 12.5 (decode-bound) | 7.6 Mbit/s |
| scale 1, 24 fps | about 12.5 (decode-bound) | 11 Mbit/s |
| scale 2, 20 fps | 20 | 3 Mbit/s |
| scale 2, 30 fps | 30 (5 runs of 5, no drops) | 4.3 Mbit/s |

Audio: no late or dropped chunks in any run. A synthetic test pattern is much lighter than real video,
so only real content says anything about the ceiling.
