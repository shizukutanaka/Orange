package com.orange.apple

import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionDataVersionTest {

    @Test fun last_updated_matches_yyyy_mm_format() {
        assertTrue(
            "LAST_UPDATED should be a YYYY-MM date string, was '${ProtectionDataVersion.LAST_UPDATED}'",
            ProtectionDataVersion.LAST_UPDATED.matches(Regex("""\d{4}-\d{2}"""))
        )
    }
}
