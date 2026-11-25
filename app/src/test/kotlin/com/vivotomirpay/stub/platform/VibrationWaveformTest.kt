package com.vivotomirpay.stub.platform

import com.vivotomirpay.stub.vibrationWaveform
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class VibrationWaveformTest {
    @Test
    fun usesLegacyVibrationWaveforms() {
        assertArrayEquals(longArrayOf(0, 40), vibrationWaveform(40, "single"))
        assertArrayEquals(longArrayOf(0, 40, 60, 40), vibrationWaveform(40, "double"))
        assertArrayEquals(longArrayOf(0, 500, 500, 500), vibrationWaveform(500, "double"))
        assertArrayEquals(longArrayOf(0, 160), vibrationWaveform(40, "long"))
        assertArrayEquals(longArrayOf(0, 600), vibrationWaveform(500, "long"))
    }

    @Test
    fun longWaveformMultipliesWithoutIntOverflow() {
        assertArrayEquals(longArrayOf(0, 600), vibrationWaveform(Int.MAX_VALUE, "long"))
        assertArrayEquals(longArrayOf(0, -8_589_934_592L), vibrationWaveform(Int.MIN_VALUE, "long"))
    }

    @Test
    fun unrecognizedOrNullPatternUsesSinglePulse() {
        assertArrayEquals(longArrayOf(0, 40), vibrationWaveform(40, "DOUBLE"))
        assertArrayEquals(longArrayOf(0, 40), vibrationWaveform(40, null))
    }
}
