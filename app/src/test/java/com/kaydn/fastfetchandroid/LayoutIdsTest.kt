package com.kaydn.fastfetchandroid

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Screens come in more than one layout version (round watches get their
 * own). Code finds views by ID, so an ID that exists in one version and
 * not the other is a crash on the devices that use the other one. v1.1
 * and v1.2 crashed on round watches exactly this way: the "Hide IPv6"
 * switch was only added to the normal settings layout.
 */
class LayoutIdsTest {

    @Test
    fun roundSettingsDialogHasEveryIdTheCodeLooksUp() {
        val usedIds =
            Regex("""R\.id\.(\w+)""")
                .findAll(source("java/com/kaydn/fastfetchandroid/SettingsDialog.kt"))
                .map { it.groupValues[1] }
                .toSet()

        assertTrue(
            "SettingsDialog.kt should look up views by R.id",
            usedIds.isNotEmpty()
        )

        for (layout in listOf("layout", "layout-round")) {
            val declaredIds = idsIn("res/$layout/dialog_username.xml")
            val missing = usedIds - declaredIds

            assertTrue(
                "$layout/dialog_username.xml is missing: $missing",
                missing.isEmpty()
            )
        }
    }

    private fun idsIn(path: String): Set<String> {
        return Regex("""@\+id/(\w+)""")
            .findAll(source(path))
            .map { it.groupValues[1] }
            .toSet()
    }

    // Unit tests run from the module folder (app/).
    private fun source(path: String): String {
        return File("src/main/$path").readText()
    }
}
