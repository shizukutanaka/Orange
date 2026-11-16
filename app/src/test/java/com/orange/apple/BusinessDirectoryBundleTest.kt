package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.StringReader

/**
 * Tests for BusinessDirectoryBundle.parseInto() — the pure CSV parsing
 * logic that has no Android dependency.
 *
 * The Android Context path (asset loading) is excluded from unit tests
 * by design; that path is covered by the privacy guard CI (which verifies
 * no network code slips in) and integration-tested during a real device
 * build. Here we focus on the parser contract.
 */
class BusinessDirectoryBundleTest {

    private fun parse(csv: String): Map<String, String> {
        val dst = mutableMapOf<String, String>()
        val reader = BufferedReader(StringReader(csv.trimIndent()))
        BusinessDirectoryBundle.parseInto(reader, dst)
        return dst
    }

    @Test fun parses_simple_entry() {
        val m = parse("+81123456789,テスト会社")
        assertEquals("テスト会社", m["+81123456789"])
    }

    @Test fun ignores_comment_lines() {
        val m = parse("""
            # This is a comment
            +81111111111,会社A
        """)
        assertEquals(1, m.size)
        assertEquals("会社A", m["+81111111111"])
    }

    @Test fun ignores_blank_lines() {
        val m = parse("""
            
            +81111111111,会社A
            
            +81222222222,会社B
        """)
        assertEquals(2, m.size)
    }

    @Test fun trims_whitespace_around_number_and_name() {
        val m = parse("  +81333333333  ,  テスト  ")
        assertEquals("テスト", m["+81333333333"])
    }

    @Test fun skips_line_without_comma() {
        val m = parse("+81444444444")
        assertTrue(m.isEmpty())
    }

    @Test fun skips_line_with_empty_name() {
        val m = parse("+81555555555,")
        assertTrue(m.isEmpty())
    }

    @Test fun skips_line_with_empty_number() {
        val m = parse(",テスト会社")
        assertTrue(m.isEmpty())
    }

    @Test fun everything_after_first_comma_is_name() {
        // Name may contain commas — use first split point only.
        val m = parse("+81666666666,会社A,追加情報")
        assertEquals("会社A,追加情報", m["+81666666666"])
    }

    @Test fun hash_in_name_is_not_treated_as_comment() {
        // Comment detection is line-start-only.
        val m = parse("+81777777777,会社#1")
        assertEquals("会社#1", m["+81777777777"])
    }

    @Test fun parses_actual_csv_entries() {
        // Spot-check three entries from the shipped business_directory.csv
        val csv = """
            +81354737800,総務省
            +81570200000,ヤマト運輸
            +81120860862,三井住友銀行
        """.trimIndent()
        val m = parse(csv)
        assertEquals("総務省", m["+81354737800"])
        assertEquals("ヤマト運輸", m["+81570200000"])
        assertEquals("三井住友銀行", m["+81120860862"])
    }

    /**
     * Regression guard: BusinessDirectoryBundle grants unconditional silent trust
     * (Layer 5 — rings with NO impersonation warning, bypasses even STIR/SHAKEN
     * checks). Numbers listed in PoliceStationDirectory or TaxAgencyDirectory are
     * exactly what ニセ警察詐欺/還付金詐欺 scammers spoof, so they must only ever
     * be reachable via their dedicated warn-but-ring directory, never via this
     * bundle's blind trust. Both 警察庁 and 国税庁 were previously mis-bundled
     * here; this checks EVERY entry in both directories generically so a future
     * addition to either can't silently reintroduce the same bug.
     *
     * Per-pack since ADR 015: each pack's business file is checked against
     * that pack's own warn directories, converting domestic keys to E.164
     * with the pack's own rule (JP: drop the trunk 0, prefix +81; US: no
     * trunk exists, prefix +1). The pre-ADR-015 version of this test
     * applied the +81 conversion to the merged directories — with a
     * second pack registered it would have fabricated non-existent keys
     * for the US entries and silently stopped guarding them.
     */
    @Test fun shipped_csv_never_bundles_a_warn_directory_number() {
        for (pack in CountryPacks.all) {
            val csvFile = File("src/main/assets/packs/${pack.iso.lowercase()}/business_directory.csv")
            assertTrue("expected asset to exist at ${csvFile.absolutePath}", csvFile.exists())
            val m = parse(csvFile.readText())

            val warnDirectories = listOf(
                "PoliceStationDirectory" to pack.policeDirectory,
                "TaxAgencyDirectory" to pack.taxDirectory,
            )
            for ((dirName, entries) in warnDirectories) {
                for ((domesticKey, name) in entries) {
                    val e164 = when (pack.iso) {
                        "JP" -> "+81" + domesticKey.removePrefix("0")
                        "US" -> "+1" + domesticKey
                        else -> error("No E.164 conversion rule for pack ${pack.iso}")
                    }
                    assertFalse("$name ($domesticKey, from $dirName) must not be silently " +
                        "trusted via the business bundle as $e164",
                        m.containsKey(e164))
                }
            }
        }
    }
}
