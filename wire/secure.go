// Package wire is the techo5-cast protocol: the encrypted connection and the messages on it.
// docs/protocol.md is the specification; echod/internal/feature/cast in the techo5 repository is the
// device's copy of this file and has to match it byte for byte on the wire.
package wire

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"

	"github.com/flynn/noise"
)

const (
	Prologue  = "techo5-cast/1"
	recordMax = 60000 // well inside Noise's 65535-byte message limit, with room for the tag
	wireMax   = recordMax + 64

	// handshakeMax is the most a handshake message may be. NNpsk0's are 48 bytes; a connection that
	// claims more, before it has shown the key, is not given the memory to say it.
	handshakeMax = 256
)

func suite() noise.CipherSuite {
	return noise.NewCipherSuite(noise.DH25519, noise.CipherChaChaPoly, noise.HashSHA256)
}

// psk is the key as Noise wants it: 32 bytes, whatever the key's length.
func psk(key string) []byte {
	sum := sha256.Sum256([]byte("techo5-cast psk:" + key))
	return sum[:]
}

// Secure is a connection after the handshake: what is written is encrypted, what is read decrypted
// and checked.
type Secure struct {
	net.Conn
	send, recv *noise.CipherState

	wmu  sync.Mutex
	rbuf []byte
}

func writeFrame(w io.Writer, b []byte) error {
	var hdr [4]byte
	binary.BigEndian.PutUint32(hdr[:], uint32(len(b)))
	_, err := w.Write(append(hdr[:], b...))
	return err
}

func readFrame(r io.Reader, most uint32) ([]byte, error) {
	var hdr [4]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(hdr[:])
	if n == 0 || n > most {
		return nil, fmt.Errorf("secure: a record of %d bytes", n)
	}
	b := make([]byte, n)
	_, err := io.ReadFull(r, b)
	return b, err
}

// Dial is the sender's side of the handshake: the phone, connecting to a device. It fails when the
// device's key is not this one.
func Dial(c net.Conn, key string) (*Secure, error) {
	hs, err := noise.NewHandshakeState(noise.Config{
		CipherSuite: suite(), Random: rand.Reader, Pattern: noise.HandshakeNN, Initiator: true,
		Prologue: []byte(Prologue), PresharedKey: psk(key), PresharedKeyPlacement: 0,
	})
	if err != nil {
		return nil, err
	}
	first, _, _, err := hs.WriteMessage(nil, nil)
	if err != nil {
		return nil, err
	}
	if err := writeFrame(c, first); err != nil {
		return nil, err
	}
	reply, err := readFrame(c, handshakeMax)
	if err != nil {
		return nil, err
	}
	_, toDevice, fromDevice, err := hs.ReadMessage(nil, reply)
	if err != nil {
		return nil, errors.New("secure: the device's key is not this one")
	}
	return &Secure{Conn: c, send: toDevice, recv: fromDevice}, nil
}

// Accept is the device's side. The repository has it for its tests; echod has its own copy.
func Accept(c net.Conn, key string) (*Secure, error) {
	hs, err := noise.NewHandshakeState(noise.Config{
		CipherSuite: suite(), Random: rand.Reader, Pattern: noise.HandshakeNN, Initiator: false,
		Prologue: []byte(Prologue), PresharedKey: psk(key), PresharedKeyPlacement: 0,
	})
	if err != nil {
		return nil, err
	}
	first, err := readFrame(c, handshakeMax)
	if err != nil {
		return nil, err
	}
	if _, _, _, err := hs.ReadMessage(nil, first); err != nil {
		return nil, errors.New("secure: the sender's key is not this device's")
	}
	reply, fromSender, toSender, err := hs.WriteMessage(nil, nil)
	if err != nil {
		return nil, err
	}
	if err := writeFrame(c, reply); err != nil {
		return nil, err
	}
	return &Secure{Conn: c, send: toSender, recv: fromSender}, nil
}

func (s *Secure) Write(p []byte) (int, error) {
	s.wmu.Lock()
	defer s.wmu.Unlock()
	total := len(p)
	for len(p) > 0 {
		n := min(len(p), recordMax)
		ct, err := s.send.Encrypt(nil, nil, p[:n])
		if err != nil {
			return total - len(p), err
		}
		if err := writeFrame(s.Conn, ct); err != nil {
			return total - len(p), err
		}
		p = p[n:]
	}
	return total, nil
}

// Read is only ever called from one goroutine, the one reading the connection.
func (s *Secure) Read(p []byte) (int, error) {
	for len(s.rbuf) == 0 {
		ct, err := readFrame(s.Conn, wireMax)
		if err != nil {
			return 0, err
		}
		pt, err := s.recv.Decrypt(nil, nil, ct)
		if err != nil {
			return 0, errors.New("secure: a record that does not check out")
		}
		s.rbuf = pt
	}
	n := copy(p, s.rbuf)
	s.rbuf = s.rbuf[n:]
	return n, nil
}
