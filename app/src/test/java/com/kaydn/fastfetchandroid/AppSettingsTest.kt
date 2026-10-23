package com.kaydn.fastfetchandroid

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks in how typed settings are cleaned up, so moving code around
 * can't quietly change what gets saved.
 */
class AppSettingsTest {

    @Test
    fun defaultOrderStaysAsIs() {
        assertEquals(
            VALID_MODULES,
            normalizeModuleOrder(DEFAULT_MODULE_ORDER)
        )
    }

    @Test
    fun typedOrderIsTrimmedAndLowercased() {
        assertEquals(
            listOf("power", "display"),
            normalizeModuleOrder(" Power ,DISPLAY").take(2)
        )
    }

    @Test
    fun unknownNamesAndRepeatsAreDropped() {
        assertEquals(
            listOf("misc", "display"),
            normalizeModuleOrder("misc,bogus,misc,display").take(2)
        )
    }

    @Test
    fun leftOutModulesAreAddedInDefaultOrder() {
        assertEquals(
            listOf(
                "misc",
                "display",
                "hardware",
                "graphics",
                "memory",
                "network",
                "power",
                "android",
                "security"
            ),
            normalizeModuleOrder("misc")
        )
    }

    @Test
    fun emptyOrderFallsBackToDefault() {
        assertEquals(
            VALID_MODULES,
            normalizeModuleOrder("")
        )
    }

    @Test
    fun usernameLosesNewlinesAndAtSigns() {
        assertEquals(
            "kaydn",
            sanitizeUsername(" ka@yd\nn\r ")
        )
    }

    @Test
    fun usernameIsCappedAt24Characters() {
        assertEquals(
            "a".repeat(24),
            sanitizeUsername("a".repeat(40))
        )
    }

    @Test
    fun blankUsernameBecomesDefault() {
        assertEquals(
            DEFAULT_USERNAME,
            sanitizeUsername("  @ \n")
        )
    }
}
