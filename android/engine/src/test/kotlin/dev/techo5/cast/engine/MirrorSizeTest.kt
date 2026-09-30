package dev.techo5.cast.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorSizeTest {
    @Test fun portraitPhoneFitsTheHeight() {
        assertEquals(216 to 480, fitEven(1080, 2400, 960, 480))
    }

    @Test fun landscapePhoneFitsTheWidth() {
        assertEquals(960 to 432, fitEven(2400, 1080, 960, 480))
    }

    @Test fun sizesAreEven() {
        val (w, h) = fitEven(1081, 2401, 960, 480)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }

    @Test fun sameShapeFillsTheBox() {
        assertEquals(960 to 480, fitEven(1920, 960, 960, 480))
    }
}
