package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Schema v1 import/export (BlockListTransfer). The export vectors and
 * the README example document are Ring's own (spam-blocker/README.md),
 * carried over from Ring's test suite so a Ring export is verified to
 * land in Orange intact.
 */
class BlockListTransferTest {

    @Test fun `export carries version and prefixes`() {
        val json = BlockListTransfer.exportJson(
            listOf(
                PrefixRule("0800", PrefixRule.SCOPE_NATIONAL),
                PrefixRule("8190", PrefixRule.SCOPE_E164),
            )
        )
        val parsed = MiniJson.parse(json) as Map<*, *>
        assertEquals(1.0, parsed["version"])
        @Suppress("UNCHECKED_CAST")
        val prefixes = parsed["prefixes"] as List<Map<String, Any?>>
        assertEquals(2, prefixes.size)
        assertEquals("0800", prefixes[0]["prefix"])
        assertEquals("national", prefixes[0]["scope"])
        assertEquals("8190", prefixes[1]["prefix"])
        assertEquals("e164", prefixes[1]["scope"])
        // Export never carries numbers Orange cannot read back (hashed
        // cache / masked history): the arrays are present but empty.
        assertEquals(emptyList<Any?>(), parsed["allow"])
        assertEquals(emptyList<Any?>(), parsed["blockExact"])
        assertEquals(emptyList<Any?>(), parsed["reports"])
    }

    @Test fun `export then import round-trips the rules`() {
        val rules = listOf(
            PrefixRule("0800", PrefixRule.SCOPE_NATIONAL),
            PrefixRule("0501234", PrefixRule.SCOPE_NATIONAL),
            PrefixRule("8190", PrefixRule.SCOPE_E164),
        )
        val imported = BlockListTransfer.importJson(BlockListTransfer.exportJson(rules))
        assertEquals(rules, imported.prefixRules)
        assertEquals(emptyList<String>(), imported.blockExact)
        assertEquals(0, imported.skippedAllow)
        assertEquals(0, imported.skippedReports)
    }

    @Test fun `ring README example imports`() {
        // The schema v1 example from Ring's spam-blocker/README.md, verbatim.
        val readmeExample = """
            {
              "version": 1,
              "allow": ["+81312345678"],
              "blockExact": ["+819012345678"],
              "prefixes": [{ "prefix": "0800", "scope": "national" }, { "prefix": "8190", "scope": "e164" }],
              "reports": [{ "number": "+815012345678", "category": "fraud", "note": "", "ts": 1762000000000 }]
            }
        """.trimIndent()
        val imported = BlockListTransfer.importJson(readmeExample)
        assertEquals(
            listOf(
                PrefixRule("0800", PrefixRule.SCOPE_NATIONAL),
                PrefixRule("8190", PrefixRule.SCOPE_E164),
            ),
            imported.prefixRules,
        )
        assertEquals(listOf("+819012345678"), imported.blockExact)
        // allow / reports have no faithful home in Orange: counted, not imported.
        assertEquals(1, imported.skippedAllow)
        assertEquals(1, imported.skippedReports)
    }

    @Test fun `version 2 rejected`() {
        try {
            BlockListTransfer.importJson("{\"version\":2}")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("unsupported blocklist version", e.message)
        }
    }

    @Test fun `junk rejected`() {
        try {
            BlockListTransfer.importJson("not json")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("invalid JSON", e.message)
        }
    }

    @Test fun `malformed entries are filtered, not fatal`() {
        val imported = BlockListTransfer.importJson(
            """{"version":1,"allow":["+811",42,null],"blockExact":["+819012345678","09012345678","+811","110"],""" +
                """"prefixes":[{"prefix":"0800","scope":"national"},{"prefix":1,"scope":"x"},null,""" +
                """{"prefix":"1234","scope":"national"},{"prefix":"8190","scope":"e164"}],""" +
                """"reports":[{"number":"+811"},{"nope":true}]}"""
        )
        // Only the well-formed E.164 exact number survives: domestic form,
        // too-short, and short-code entries are dropped by the E.164 bar.
        assertEquals(listOf("+819012345678"), imported.blockExact)
        assertEquals(
            listOf(
                PrefixRule("0800", PrefixRule.SCOPE_NATIONAL),
                PrefixRule("8190", PrefixRule.SCOPE_E164),
            ),
            imported.prefixRules,
        )
        assertEquals(3, imported.skippedAllow)
        assertEquals(2, imported.skippedReports)
    }

    @Test fun `short emergency form in blockExact is dropped`() {
        val imported = BlockListTransfer.importJson(
            """{"version":1,"blockExact":["+81110"]}"""
        )
        assertTrue(imported.blockExact.isEmpty())
    }

    @Test fun `duplicate entries collapse`() {
        val imported = BlockListTransfer.importJson(
            """{"version":1,"blockExact":["+819012345678","+819012345678"],""" +
                """"prefixes":[{"prefix":"0800","scope":"national"},{"prefix":"0800","scope":"national"}]}"""
        )
        assertEquals(listOf("+819012345678"), imported.blockExact)
        assertEquals(listOf(PrefixRule("0800", PrefixRule.SCOPE_NATIONAL)), imported.prefixRules)
    }
}
