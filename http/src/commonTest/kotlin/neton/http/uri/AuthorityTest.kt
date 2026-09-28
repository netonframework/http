package neton.http.uri

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Port of the 18 tests in http 1.5.0 `src/uri/authority.rs`.
class AuthorityTest {

    /** `Authority::parse_non_empty(..).unwrap_err().0` */
    private fun parseNonEmptyErr(s: String): InvalidUri.ErrorKind {
        val r = Authority.parseNonEmpty(s, 0, s.length)
        assertTrue(r < 0, "expected an error for \"$s\"")
        return Authority.decodeError(r)
    }

    @Test
    fun parseEmptyStringIsError() {
        assertEquals(InvalidUri.ErrorKind.Empty, parseNonEmptyErr(""))
    }

    @Test
    fun equalToSelfOfSameAuthority() {
        val authority1 = Authority.parse("example.com")
        val authority2 = Authority.parse("EXAMPLE.COM")
        assertEquals(authority1, authority2)
        assertEquals(authority2, authority1)
    }

    @Test
    fun notEqualToSelfOfDifferentAuthority() {
        val authority1 = Authority.parse("example.com")
        val authority2 = Authority.parse("test.com")
        assertNotEquals(authority1, authority2)
        assertNotEquals(authority2, authority1)
    }

    @Test
    fun equatesWithAStr() {
        val authority = Authority.parse("example.com")
        assertTrue(authority eq "EXAMPLE.com")
        assertTrue("EXAMPLE.com" eq authority)
    }

    @Test
    fun fromStaticEquatesWithAStr() {
        val authority = Authority.fromStatic("example.com")
        assertTrue(authority eq "example.com")
    }

    @Test
    fun notEqualWithAStrOfADifferentAuthority() {
        val authority = Authority.parse("example.com")
        assertFalse(authority eq "test.com")
        assertFalse("test.com" eq authority)
    }

    @Test
    fun equatesWithAString() {
        val authority = Authority.parse("example.com")
        assertTrue(authority eq "EXAMPLE.com")
        assertTrue("EXAMPLE.com" eq authority)
    }

    @Test
    fun equatesWithAStringOfADifferentAuthority() {
        val authority = Authority.parse("example.com")
        assertFalse(authority eq "test.com")
        assertFalse("test.com" eq authority)
    }

    @Test
    fun comparesToSelf() {
        val authority1 = Authority.parse("abc.com")
        val authority2 = Authority.parse("def.com")
        assertTrue(authority1 < authority2)
        assertTrue(authority2 > authority1)
    }

    @Test
    fun comparesWithAStr() {
        val authority = Authority.parse("def.com")
        assertTrue(authority < "ghi.com")
        assertTrue("ghi.com" > authority)
        assertTrue(authority > "abc.com")
        assertTrue("abc.com" < authority)
    }

    @Test
    fun comparesWithAString() {
        val authority = Authority.parse("def.com")
        assertTrue(authority < "ghi.com")
        assertTrue("ghi.com" > authority)
        assertTrue(authority > "abc.com")
        assertTrue("abc.com" < authority)
    }

    @Test
    fun allowsPercentInUserinfo() {
        val authorityStr = "a%2f:b%2f@example.com"
        val authority = Authority.parse(authorityStr)
        assertTrue(authority eq authorityStr)
    }

    @Test
    fun rejectsPercentInHostname() {
        assertEquals(InvalidUri.ErrorKind.InvalidAuthority, parseNonEmptyErr("example%2f.com"))
        assertEquals(InvalidUri.ErrorKind.InvalidAuthority, parseNonEmptyErr("a%2f:b%2f@example%2f.com"))
    }

    @Test
    fun allowsPercentInIpv6Address() {
        val authorityStr = "[fe80::1:2:3:4%25eth0]"
        val result = Authority.parse(authorityStr)
        assertTrue(result eq authorityStr)
    }

    @Test
    fun rejectObviouslyInvalidIpv6Address() {
        assertEquals(InvalidUri.ErrorKind.InvalidAuthority, parseNonEmptyErr("[0:1:2:3:4:5:6:7:8:9:10:11:12:13:14]"))
    }

    @Test
    fun rejectsPercentOutsideIpv6Address() {
        assertEquals(InvalidUri.ErrorKind.InvalidAuthority, parseNonEmptyErr("1234%20[fe80::1:2:3:4]"))
        assertEquals(InvalidUri.ErrorKind.InvalidAuthority, parseNonEmptyErr("[fe80::1:2:3:4]%20"))
    }

    @Test
    fun rejectsInvalidUtf8() {
        val bytes = byteArrayOf(0xc0.toByte())
        val err = kotlin.test.assertFailsWith<InvalidUri> { Authority.fromBytes(bytes) }
        assertEquals(InvalidUri.ErrorKind.InvalidUriChar, err.kind)
        // `from_shared` path of the reference: same entry point here.
        assertEquals(null, Authority.tryFromBytes(bytes))
    }

    @Test
    fun rejectsInvalidUseOfBrackets() {
        assertEquals(InvalidUri.ErrorKind.InvalidAuthority, parseNonEmptyErr("[]@["))
        // reject tie-fighter
        assertEquals(InvalidUri.ErrorKind.InvalidAuthority, parseNonEmptyErr("]o["))
    }
}
