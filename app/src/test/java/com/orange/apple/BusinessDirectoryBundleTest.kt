package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
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
}
