package com.company.callmonitor.callerlookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerLookupLogicTest {

    @Test
    fun normalizeDigits_stripsCountryAndTrunkPrefix() {
        assertEquals("9876543210", AndroidCallLogLookupProvider.normalizeDigits("+91 98765-43210"))
        assertEquals("9876543210", AndroidCallLogLookupProvider.normalizeDigits("919876543210"))
        assertEquals("9876543210", AndroidCallLogLookupProvider.normalizeDigits("09876543210"))
        assertEquals("9876543210", AndroidCallLogLookupProvider.normalizeDigits("9876543210"))
    }

    @Test
    fun lookupVariants_includeCommonIndianFormats() {
        val variants = AndroidCallLogLookupProvider.lookupVariants("9876543210").toList()
        assertTrue(variants.contains("9876543210"))
        assertTrue(variants.contains("+919876543210"))
        assertTrue(variants.contains("919876543210"))
        assertTrue(variants.contains("09876543210"))
    }

    @Test
    fun numberAt_walksSeries() {
        assertEquals("9203000000", CallerLookupRunner.numberAt("9203000000", 0))
        assertEquals("9203000001", CallerLookupRunner.numberAt("9203000000", 1))
        assertEquals("9203000199", CallerLookupRunner.numberAt("9203000000", 199))
    }

    @Test
    fun seriesStartEnd_matchPrefixCapacity() {
        val series = NumberSeries("jio-92030", "jio", "92030", "92030 — Madhya Pradesh")
        assertEquals("9203000000", series.startNumber())
        assertEquals("9203099999", series.endNumber())
    }
}
