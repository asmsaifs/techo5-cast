// castsend casts a video file or URL to a device the way the phone will: JPEG frames and PCM audio over
// the encrypted connection, stamped with one clock. It is the test bench for the device, and a way to
// try real content before there is a phone app.
//
//	castsend -key KEY -i clip.mp4                       finds a device over mDNS
//	castsend -addr 192.168.1.50:8940 -key KEY -i https://example.com/film.mp4
//	castsend -key KEY -i 'https://www.youtube.com/watch?v=…'   (needs yt-dlp)
//
// The key can also come from CASTKEY. Ctrl-C ends the cast; so does a swipe in from the left edge of
// the device's screen.
package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/libp2p/zeroconf/v2"

	"techno5-cast/wire"
)

const (
	rate     = 48000
	channels = 2
	chunk    = 20 * time.Millisecond // audio sent in pieces this long
	service  = "_techno5cast._tcp"
)

type options struct {
	addr, name, key, in string
	w, h, fps, q, scale int
	audio               bool
	start, length       string
	quiet               bool
}

func main() {
	var o options
	flag.StringVar(&o.addr, "addr", "", "device address, host:port (default: find one over mDNS)")
	flag.StringVar(&o.name, "name", "", "with mDNS, the device to pick when there are several")
	flag.StringVar(&o.key, "key", os.Getenv("CASTKEY"), "pairing key (or set CASTKEY)")
	flag.StringVar(&o.in, "i", "", "video file or URL; YouTube and the like need yt-dlp")
	flag.IntVar(&o.w, "w", 960, "frame width; the device's screen, if it says otherwise")
	flag.IntVar(&o.h, "h", 480, "frame height")
	flag.IntVar(&o.fps, "fps", 15, "frames per second")
	flag.IntVar(&o.q, "q", 6, "ffmpeg JPEG quality, 2 (best) to 31")
	flag.IntVar(&o.scale, "scale", 1, "1 for full-size frames, 2 for half-size drawn doubled (a quarter of the device's decoding)")
	noAudio := flag.Bool("no-audio", false, "video only")
	flag.StringVar(&o.start, "ss", "", "start this far into the video (ffmpeg time, e.g. 1:30)")
	flag.StringVar(&o.length, "t", "", "stop after this long (ffmpeg time, e.g. 60)")
	flag.BoolVar(&o.quiet, "quiet", false, "no progress lines")
	list := flag.Bool("list", false, "list the devices found over mDNS and stop")
	flag.Parse()
	o.audio = !*noAudio

	if *list {
		devs, err := discover(4 * time.Second)
		if err != nil {
			log.Fatal(err)
		}
		for _, d := range devs {
			fmt.Printf("%-30s %s\n", d.name, d.addr)
		}
		if len(devs) == 0 {
			fmt.Println("no devices found (is Cast switched on?)")
		}
		return
	}
	if o.key == "" || o.in == "" {
		flag.Usage()
		os.Exit(2)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, o); err != nil && !errors.Is(err, context.Canceled) {
		log.Fatal(err)
	}
}

type device struct{ name, addr string }

// discover browses for devices for the given time.
func discover(wait time.Duration) ([]device, error) {
	ctx, cancel := context.WithTimeout(context.Background(), wait)
	defer cancel()
	entries := make(chan *zeroconf.ServiceEntry)
	var out []device
	done := make(chan struct{})
	go func() {
		defer close(done)
		for e := range entries {
			var ip net.IP
			if len(e.AddrIPv4) > 0 {
				ip = e.AddrIPv4[0]
			} else {
				continue
			}
			out = append(out, device{strings.ReplaceAll(e.Instance, `\ `, " "), net.JoinHostPort(ip.String(), fmt.Sprint(e.Port))})
		}
	}()
	if err := zeroconf.Browse(ctx, service, "local.", entries); err != nil {
		return nil, err
	}
	<-ctx.Done()
	<-done
	return out, nil
}

func pick(o options) (string, error) {
	if o.addr != "" {
		return o.addr, nil
	}
	log.Print("looking for a device over mDNS…")
	devs, err := discover(4 * time.Second)
	if err != nil {
		return "", err
	}
	for _, d := range devs {
		if o.name == "" || strings.Contains(strings.ToLower(d.name), strings.ToLower(o.name)) {
			log.Printf("found %s at %s", d.name, d.addr)
			return d.addr, nil
		}
	}
	return "", errors.New("no device found; is Cast switched on? try -addr host:port")
}

// source is what ffmpeg is to read: a file or a URL as it is, or a page yt-dlp turns into streams. audio
// is empty when the picture's own input carries the sound; YouTube and the like keep them apart.
func source(ctx context.Context, in string) (video, audio string, err error) {
	u, err := url.Parse(in)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
		return in, "", nil // a file, or something ffmpeg reads by itself (rtsp://…)
	}
	// A direct media URL goes straight to ffmpeg; a page is yt-dlp's to resolve.
	if p := strings.ToLower(u.Path); strings.HasSuffix(p, ".mp4") || strings.HasSuffix(p, ".m3u8") ||
		strings.HasSuffix(p, ".webm") || strings.HasSuffix(p, ".mkv") || strings.HasSuffix(p, ".mov") ||
		strings.HasSuffix(p, ".ts") || strings.HasSuffix(p, ".mp3") {
		return in, "", nil
	}
	if _, err := exec.LookPath("yt-dlp"); err != nil {
		return "", "", errors.New("that is a web page, not a video file: install yt-dlp (brew install yt-dlp) to cast it")
	}
	log.Print("asking yt-dlp for the stream…")
	// Small: this device shows 960×480. Most videos come as a picture stream and a sound stream with no
	// combined one, and then yt-dlp prints two URLs, the picture's first.
	out, err := exec.CommandContext(ctx, "yt-dlp", "--no-playlist", "-f", "bv*[height<=720]+ba/b[height<=720]/b", "-g", in).Output()
	if err != nil {
		return "", "", fmt.Errorf("yt-dlp: %w", err)
	}
	lines := strings.Fields(string(out))
	switch len(lines) {
	case 0:
		return "", "", errors.New("yt-dlp found no stream")
	case 1:
		return lines[0], "", nil
	}
	return lines[0], lines[1], nil
}

// hasAudio is whether ffprobe finds an audio stream in src. When ffprobe is not there, or cannot tell,
// audio is assumed and ffmpeg's own mapping ("0:a:0?") copes with there being none.
func hasAudio(ctx context.Context, src string) bool {
	out, err := exec.CommandContext(ctx, "ffprobe", "-v", "error", "-select_streams", "a",
		"-show_entries", "stream=index", "-of", "csv=p=0", src).Output()
	if err != nil {
		return true
	}
	return len(bytes.TrimSpace(out)) > 0
}

func run(ctx context.Context, o options) error {
	addr, err := pick(o)
	if err != nil {
		return err
	}
	src, asrc, err := source(ctx, o.in)
	if err != nil {
		return err
	}

	if o.audio && asrc == "" && !hasAudio(ctx, src) {
		log.Print("no audio track: casting the picture only")
		o.audio = false
	}

	raw, err := net.DialTimeout("tcp", addr, 5*time.Second)
	if err != nil {
		return err
	}
	defer raw.Close()
	_ = raw.SetDeadline(time.Now().Add(10 * time.Second))
	c, err := wire.Dial(raw, o.key)
	if err != nil {
		return err
	}
	host, _ := os.Hostname()
	hello, _ := json.Marshal(wire.Hello{Name: host, Video: true, Audio: o.audio, Rate: rate, Channels: channels, Scale: o.scale})
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
		o.w, o.h = wel.W, wel.H
	}
	log.Printf("connected: %dx%d at %d fps, device buffers %d ms", o.w, o.h, o.fps, wel.LatencyMs)

	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

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

	go func() { // what the device says: a stop ends the cast
		for {
			kind, payload, err := wire.Read(c)
			if err != nil {
				cancel()
				return
			}
			if kind == wire.KindStop {
				log.Printf("the device ended the cast: %s", payload)
				cancel()
				return
			}
		}
	}()
	go func() { // the clock, about once a second
		t := time.NewTicker(time.Second)
		defer t.Stop()
		for {
			select {
			case <-t.C:
				if err := send(wire.KindClock, wire.Stamp(us())); err != nil {
					cancel()
					return
				}
			case <-ctx.Done():
				return
			}
		}
	}()

	st := &stats{}
	err = play(ctx, o, src, asrc, t0, send, st)
	_ = send(wire.KindBye)
	st.report(time.Since(t0))
	if ctx.Err() != nil {
		return nil // stopped by Ctrl-C or by the device
	}
	return err
}

type stats struct {
	frames, bytes, chunks atomic.Int64
}

func (s *stats) report(d time.Duration) {
	f, b := s.frames.Load(), s.bytes.Load()
	log.Printf("sent %d frames (%.1f fps) and %.1f s of audio, %.1f MB, in %.1f s",
		f, float64(f)/d.Seconds(), float64(s.chunks.Load())*chunk.Seconds(), float64(b)/1e6, d.Seconds())
}

// play runs one ffmpeg that makes both streams, so picture and sound come from the same demuxer at
// the same pace, and sends what it makes.
func play(ctx context.Context, o options, src, asrc string, t0 time.Time, send func(byte, ...[]byte) error, st *stats) error {
	w, h := o.w/o.scale, o.h/o.scale
	vf := fmt.Sprintf("fps=%d,scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2",
		o.fps, w, h, w, h)
	args := []string{"-loglevel", "error"}
	input := func(u string) {
		args = append(args, "-re")
		if o.start != "" {
			args = append(args, "-ss", o.start)
		}
		if o.length != "" {
			args = append(args, "-t", o.length) // before -i, so it limits the input and so both streams
		}
		args = append(args, "-i", u)
	}
	input(src)
	amap := "0:a:0?"
	if asrc != "" {
		input(asrc)
		amap = "1:a:0"
	}
	args = append(args, "-map", "0:v:0", "-an", "-vf", vf, "-q:v", fmt.Sprint(o.q), "-f", "image2pipe", "-c:v", "mjpeg", "pipe:1")
	var aw *os.File
	var ar *os.File
	if o.audio {
		var err error
		if ar, aw, err = os.Pipe(); err != nil {
			return err
		}
		args = append(args, "-map", amap, "-vn", "-ar", fmt.Sprint(rate), "-ac", fmt.Sprint(channels), "-f", "s16le", "pipe:3")
	}

	cmd := exec.CommandContext(ctx, "ffmpeg", args...)
	cmd.Stderr = os.Stderr
	vout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	if aw != nil {
		cmd.ExtraFiles = []*os.File{aw} // fd 3
	}
	if err := cmd.Start(); err != nil {
		return err
	}
	if aw != nil {
		aw.Close() // the child has it; this end must not, or the reader never sees the end
	}

	errs := make(chan error, 2)
	go func() { errs <- video(vout, o, t0, send, st) }()
	if ar != nil {
		go func() { errs <- pcm(ar, t0, send, st) }()
	}
	n := 1
	if ar != nil {
		n = 2
	}
	var first error
	for i := 0; i < n; i++ {
		if err := <-errs; err != nil && first == nil {
			first = err
		}
	}
	if err := cmd.Wait(); err != nil && first == nil && ctx.Err() == nil {
		first = err
	}
	return first
}

func video(r io.Reader, o options, t0 time.Time, send func(byte, ...[]byte) error, st *stats) error {
	br := bufio.NewReaderSize(r, 1<<20)
	var base time.Duration
	report, last := time.Now(), int64(0)
	for i := 0; ; i++ {
		jpg, err := nextJPEG(br)
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		if i == 0 {
			base = time.Since(t0) // ffmpeg's start-up is not part of the clip
		}
		stamp := base + time.Duration(i)*time.Second/time.Duration(o.fps)
		if err := send(wire.KindVideo, wire.Stamp(stamp.Microseconds()), jpg); err != nil {
			return err
		}
		st.frames.Add(1)
		st.bytes.Add(int64(len(jpg)))
		if !o.quiet && time.Since(report) > 5*time.Second {
			n := st.frames.Load()
			log.Printf("video: %d frames, %.1f fps", n, float64(n-last)/time.Since(report).Seconds())
			report, last = time.Now(), n
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

// pcm sends the audio in 20 ms pieces, stamped by how much has been sent.
func pcm(r io.Reader, t0 time.Time, send func(byte, ...[]byte) error, st *stats) error {
	var base time.Duration
	buf := make([]byte, int(chunk/time.Millisecond)*rate/1000*channels*2)
	for n := 0; ; n++ {
		if _, err := io.ReadFull(r, buf); err != nil {
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
		st.chunks.Add(1)
	}
}
