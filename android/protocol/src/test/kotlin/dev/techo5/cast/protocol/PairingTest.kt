package dev.techo5.cast.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {
    @Test fun readsWhatTheShowDraws() {
        // As Go's url.Values.Encode makes it: sorted keys, spaces as +.
        val p = parsePairing("techo5cast://pair?host=192.168.1.20&key=ABCDEFGH23456789&name=Kitchen+Show&port=8940")
        assertEquals(Pairing("192.168.1.20", 8940, "ABCDEFGH23456789", "Kitchen Show"), p)
    }

    @Test fun decodesEscapesInTheKey() {
        val p = parsePairing("techo5cast://pair?host=h&key=a%26b%3Dc+d&port=1")
        assertEquals("a&b=c d", p?.key)
    }

    @Test fun portDefaultsAndNameIsOptional() {
        val p = parsePairing("techo5cast://pair?host=h&key=k")
        assertEquals(Pairing("h", 8940, "k", ""), p)
    }

    @Test fun ignoresAnythingElse() {
        assertNull(parsePairing("https://example.com/?host=h&key=k"))
        assertNull(parsePairing("techo5cast://other?host=h&key=k"))
        assertNull(parsePairing("techo5cast://pair?host=h"))
        assertNull(parsePairing("techo5cast://pair?key=k"))
        assertNull(parsePairing("techo5cast://pair?host=h&key=k&port=99999"))
        assertNull(parsePairing("not a link at all"))
    }
}
