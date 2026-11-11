package xyz.grtlabs.pilastri.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.grtlabs.pilastri.data.PaydayLine

/** 1b-02: the income card swatch is outlined exactly when the line is 0 €, for any pillar. Synthetic lines only. */
class SwatchTest {
    @Test fun zeroCentLineIsOutlinedForEveryPillar() {
        (1..4).forEach { p -> assertTrue("P$p at 0 €", swatchOutlined(PaydayLine(p, 0, "x").cents)) }
    }

    @Test fun positiveLineIsFilled() {
        (1..4).forEach { p -> assertFalse("P$p at 1 cent", swatchOutlined(PaydayLine(p, 1, "x").cents)) }
        assertFalse(swatchOutlined(250_000))
    }
}
