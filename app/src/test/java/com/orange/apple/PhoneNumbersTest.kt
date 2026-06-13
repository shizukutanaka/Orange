package com.orange.apple

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumbersTest {

    @Test fun `strips spaces and hyphens`() =
        assertEquals("+81335814321", PhoneNumbers.normalize("+81 (3) 3581-4321"))

    @Test fun `domestic with hyphens`() =
        assertEquals("09012345678", PhoneNumbers.normalize("090-1234-5678"))

    @Test fun `empty string stays empty`() =
        assertEquals("", PhoneNumbers.normalize(""))

    @Test fun `full-width plus and digits are folded`() =
        assertEquals("+819012345678", PhoneNumbers.normalize("＋８１９０１２３４５６７８"))

    @Test fun `full-width digits only`() =
        assertEquals("09012345678", PhoneNumbers.normalize("０９０１２３４５６７８"))

    @Test fun `plus only valid as first character`() {
        // A raw string with '+' embedded later (e.g. user typed "++81...") must
        // keep only the leading '+'; the second '+' is noise.
        assertEquals("+819012345678", PhoneNumbers.normalize("++819012345678"))
    }

    @Test fun `plus in middle is discarded`() =
        assertEquals("081901234", PhoneNumbers.normalize("081+901234"))

    @Test fun `leading plus kept, no digits after`() =
        assertEquals("+", PhoneNumbers.normalize("+"))

    @Test fun `non-numeric non-plus chars stripped`() =
        assertEquals("0355551234", PhoneNumbers.normalize("(03)5555-1234"))
}
