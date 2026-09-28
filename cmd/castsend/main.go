// castsend sends a video file to a device the way the phone will: JPEG frames and PCM audio over the
// encrypted connection, stamped with one clock. It is the test bench for the device: how many frames
// a second it can show, and whether sound and picture stay together.
//
//	castsend -addr 192.168.1.50:8940 -key pairing-key -i clip.mp4 [-w 960 -h 480 -fps 10 -q 6]
package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"sync"
	"time"

	"techno5-cast/wire"
)

const (
	rate     = 48000
	channels = 2
	chunk    = 20 * time.Millisecond // audio sent in pieces this long
)

func main() {
	addr := flag.String("addr", "", "device address, host:port")
	key := flag.String("key", "", "pairing key")
	in := flag.String("i", "", "video file (anything ffmpeg reads)")
	w := flag.Int("w", 960, "frame width; the device's screen, if it says otherwise")
	h := flag.Int("h", 480, "frame height")
	fps := flag.Int("fps", 10, "frames per second")
	q := flag.Int("q", 6, "ffmpeg JPEG quality, 2 (best) to 31")
	noAudio := flag.Bool("no-audio", false, "video only")
	flag.Parse()
	if *addr == "" || *key == "" || *in == "" {
		flag.Usage()
		os.Exit(2)
	}
	if err := run(*addr, *key, *in, *w, *h, *fps, *q, !*noAudio); err != nil {
		log.Fatal(err)
	}
}

func run(addr, key, in string, w, h, fps, q int, audio bool) error {
	raw, err := net.DialTimeout("tcp", addr, 5*time.Second)
	if err != nil {
		return err
	}
	defer raw.Close()
	_ = raw.SetDeadline(time.Now().Add(10 * time.Second))
	c, err := wire.Dial(raw, key)
	if err != nil {
		return err
	}
	name, _ := os.Hostname()
	hello, _ := json.Marshal(wire.Hello{Name: name, Video: true, Audio: audio, Rate: rate, Channels: channels})
	if err := wire.Write(c, wire.KindHello, hello); err != nil {
		return err
	}
	kind, payload, err := wire.Read(c)
	if err != nil {
		return err
	}
	if kind != wire.KindWelcome {
		return fmt.Errorf("expected a welcome, got kind %d", kind)
	}
	var wel wire.Welcome
	if err := json.Unmarshal(payload, &wel); err != nil {
		return err
	}
	if !wel.OK {
		return errors.New("the device said no: " + wel.Reason)
	}
	_ = raw.SetDeadline(time.Time{})
	if wel.W > 0 && wel.H > 0 {
		w, h = wel.W, wel.H
	}
	log.Printf("connected: %dx%d, device buffers %d ms", w, h, wel.LatencyMs)

	t0 := time.Now()
	us := func() int64 { return time.Since(t0).Microseconds() }
	var mu sync.Mutex // one message at a time onto the connection
	send := func(kind byte, parts ...[]byte) error {
		mu.Lock()
		defer mu.Unlock()
		return wire.Write(c, kind, parts...)
	}
	if err := send(wire.KindClock, wire.Stamp(us())); err != nil {
		return err
	}

	errs := make(chan error, 3)
	done := make(chan struct{})
	go func() { // the clock, about once a second
		t := time.NewTicker(time.Second)
		defer t.Stop()
		for {
			select {
			case <-t.C:
				if err := send(wire.KindClock, wire.Stamp(us())); err != nil {
					errs <- err
					return
				}
			case <-done:
				return
			}
		}
	}()

	var streams sync.WaitGroup
	streams.Add(1)
	go func() { defer streams.Done(); errs <- video(in, w, h, fps, q, t0, send) }()
	if audio {
		streams.Add(1)
		go func() { defer streams.Done(); errs <- pcm(in, t0, send) }()
	}
	go func() { streams.Wait(); close(errs) }()

	var first error
	for err := range errs {
		if err != nil && first == nil {
			first = err
		}
	}
	close(done)
	_ = send(wire.KindBye)
	return first
}

// video sends frames as ffmpeg makes them, at real time, each stamped with its place in the clip.
func video(in string, w, h, fps, q int, t0 time.Time, send func(byte, ...[]byte) error) error {
	vf := fmt.Sprintf("scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2", w, h, w, h)
	cmd := exec.Command("ffmpeg", "-loglevel", "error", "-re", "-i", in, "-an",
		"-vf", vf, "-r", fmt.Sprint(fps), "-q:v", fmt.Sprint(q), "-f", "image2pipe", "-c:v", "mjpeg", "pipe:1")
	out, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return err
	}
	defer cmd.Wait()

	r := bufio.NewReaderSize(out, 1<<20)
	var sent int
	var base time.Duration
	report := time.Now()
	for i := 0; ; i++ {
		jpg, err := nextJPEG(r)
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		if i == 0 {
			base = time.Since(t0) // ffmpeg's start-up is not part of the clip
		}
		stamp := base + time.Duration(i)*time.Second/time.Duration(fps)
		if err := send(wire.KindVideo, wire.Stamp(stamp.Microseconds()), jpg); err != nil {
			return err
		}
		sent += len(jpg)
		if time.Since(report) > 5*time.Second {
			log.Printf("video: %d frames, %.0f kbit/s", i+1, float64(sent)*8/time.Since(report).Seconds()/1000)
			sent, report = 0, time.Now()
		}
	}
}

// nextJPEG reads one JPEG from a stream of them: from its start marker to its end marker. ffmpeg's
// frames carry no thumbnails, and an end marker cannot occur inside the scan (0xFF is stuffed).
func nextJPEG(r *bufio.Reader) ([]byte, error) {
	var b bytes.Buffer
	var prev byte
	in := false
	for {
		c, err := r.ReadByte()
		if err != nil {
			return nil, err
		}
		if !in {
			if prev == 0xFF && c == 0xD8 {
				in = true
				b.Write([]byte{0xFF, 0xD8})
			}
			prev = c
			continue
		}
		b.WriteByte(c)
		if prev == 0xFF && c == 0xD9 {
			return b.Bytes(), nil
		}
		prev = c
	}
}

// pcm sends the audio in 20 ms pieces at real time, stamped by how much has been sent.
func pcm(in string, t0 time.Time, send func(byte, ...[]byte) error) error {
	cmd := exec.Command("ffmpeg", "-loglevel", "error", "-re", "-i", in, "-vn",
		"-ar", fmt.Sprint(rate), "-ac", fmt.Sprint(channels), "-f", "s16le", "pipe:1")
	out, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return err
	}
	defer cmd.Wait()

	var base time.Duration
	buf := make([]byte, int(chunk/time.Millisecond)*rate/1000*channels*2)
	for n := 0; ; n++ {
		if _, err := io.ReadFull(out, buf); err != nil {
			if err == io.EOF || err == io.ErrUnexpectedEOF {
				return nil
			}
			return err
		}
		if n == 0 {
			base = time.Since(t0)
		}
		stamp := base + time.Duration(n)*chunk
		if err := send(wire.KindAudio, wire.Stamp(stamp.Microseconds()), buf); err != nil {
			return err
		}
	}
}
