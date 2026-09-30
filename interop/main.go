// interop is a small tool used only by the Android protocol module's tests
// (android/protocol/src/test/kotlin/.../WireTest.kt) to prove the Kotlin Noise handshake and message
// framing against the real Go implementation, not just against itself.
//
// As a server, it accepts one connection, does the responder's side of the handshake
// (wire.Accept), then echoes every message it reads back to the sender until one comes with
// kind KindBye, which it also echoes before closing. As a client, it dials in, does the
// initiator's side (wire.Dial), and does the same echo loop the other way, so both roles of the
// handshake are exercised from the Go side.
//
//	interop -role server -addr :0 -key a-test-key    (prints "ready <port>" once listening)
//	interop -role client -addr host:port -key a-test-key
package main

import (
	"flag"
	"fmt"
	"log"
	"net"
	"os"

	"techo5-cast/wire"
)

func main() {
	role := flag.String("role", "", "server or client")
	addr := flag.String("addr", "", "listen or dial address")
	key := flag.String("key", "", "pairing key")
	flag.Parse()

	switch *role {
	case "server":
		if err := runServer(*addr, *key); err != nil {
			log.Fatal(err)
		}
	case "client":
		if err := runClient(*addr, *key); err != nil {
			log.Fatal(err)
		}
	default:
		fmt.Fprintln(os.Stderr, "usage: interop -role server|client -addr ... -key ...")
		os.Exit(2)
	}
}

func runServer(addr, key string) error {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	// The test harness reads this line to learn the port when -addr is ":0".
	fmt.Printf("ready %d\n", ln.Addr().(*net.TCPAddr).Port)
	os.Stdout.Sync()

	conn, err := ln.Accept()
	if err != nil {
		return err
	}
	defer conn.Close()

	secure, err := wire.Accept(conn, key)
	if err != nil {
		return fmt.Errorf("accept: %w", err)
	}
	return echoLoop(secure)
}

func runClient(addr, key string) error {
	conn, err := net.Dial("tcp", addr)
	if err != nil {
		return err
	}
	defer conn.Close()

	secure, err := wire.Dial(conn, key)
	if err != nil {
		return fmt.Errorf("dial: %w", err)
	}
	return echoLoop(secure)
}

// echoLoop reads messages and sends each one straight back, so a test can see its own message
// return through the peer's Noise session and message framing. It stops after echoing a KindBye.
func echoLoop(secure *wire.Secure) error {
	for {
		kind, payload, err := wire.Read(secure)
		if err != nil {
			return fmt.Errorf("read: %w", err)
		}
		if err := wire.Write(secure, kind, payload); err != nil {
			return fmt.Errorf("write: %w", err)
		}
		if kind == wire.KindBye {
			return nil
		}
	}
}
