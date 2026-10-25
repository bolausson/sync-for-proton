package me.proton.sync.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a failed Proton call leaves in a log: the Code and a cleaned Error, and nothing else from the body.
 *
 * The rest of a body is not ours to log. A 9001 carries a bearer token for the sign-in in Details, a
 * refresh reply carries tokens, and an Error can quote someone's address. Bodies here are synthetic.
 */
class ProtonApiErrorTest {

    @Test
    fun `a wrong password reads as its Code and Error`() {
        val body = """{"Code":8002,"Error":"Incorrect login credentials. Please try again"}"""
        assertEquals(" (Code 8002: Incorrect login credentials. Please try again)", ProtonApiError.describe(body))
        assertEquals(8002 to "Incorrect login credentials. Please try again", ProtonApiError.parse(body))
    }

    @Test
    fun `a refused verification token reads as its Code and Error`() {
        val body = """{"Code":12087,"Error":"Invalid verification token"}"""
        assertEquals(" (Code 12087: Invalid verification token)", ProtonApiError.describe(body))
    }

    /** Details holds the human-verification token and the page that carries it; neither may appear. */
    @Test
    fun `a human verification body never yields its token or page`() {
        val body = """{"Code":9001,"Error":"Human verification required","Details":{""" +
            """"HumanVerificationToken":"HV-SECRET-TOKEN-0123456789",""" +
            """"WebUrl":"https://verify.proton.me/?token=HV-SECRET-TOKEN-0123456789&methods=captcha",""" +
            """"HumanVerificationMethods":["captcha"]}}"""
        val out = ProtonApiError.describe(body)
        assertEquals(" (Code 9001: Human verification required)", out)
        for (leak in listOf("HV-SECRET-TOKEN-0123456789", "token=", "verify.proton.me", "Details")) {
            assertFalse("leaked $leak", out.contains(leak))
        }
    }

    @Test
    fun `a refresh reply never yields a token`() {
        val ok = """{"Code":1000,"AccessToken":"ACCESS-SECRET-1","RefreshToken":"REFRESH-SECRET-1","UID":"uid-1"}"""
        assertEquals(" (Code 1000)", ProtonApiError.describe(ok))
        val refused = """{"Code":10013,"Error":"Invalid refresh token","AccessToken":"ACCESS-SECRET-2","RefreshToken":"REFRESH-SECRET-2"}"""
        val out = ProtonApiError.describe(refused)
        assertEquals(" (Code 10013: Invalid refresh token)", out)
        assertFalse(out.contains("SECRET"))
    }

    @Test
    fun `an address in the Error is masked`() {
        val body = """{"Code":2000,"Error":"Recipient someone@example.com could not be found"}"""
        val out = ProtonApiError.describe(body)
        assertEquals(" (Code 2000: Recipient <address> could not be found)", out)
        assertFalse(out.contains("@"))
    }

    @Test
    fun `markup is dropped, with the address inside it`() {
        val error = "<b>Account disabled.</b> Write to <a href=\"mailto:abuse@example.com\">our team</a> about it"
        val out = ProtonApiError.clean(error)!!
        assertEquals("Account disabled. Write to our team about it", out)
        assertFalse(out.contains("<"))
        assertFalse(out.contains("@"))
    }

    /** A body must not be able to forge extra log lines. */
    @Test
    fun `an Error with line breaks becomes one line`() {
        val body = """{"Code":2001,"Error":"first line\nsecond line\r\n  third"}"""
        assertEquals(" (Code 2001: first line second line third)", ProtonApiError.describe(body))
    }

    @Test
    fun `a long Error is cut`() {
        val out = ProtonApiError.clean("z".repeat(500))!!
        assertEquals("z".repeat(ProtonApiError.MAX_ERROR_CHARS) + "…", out)
        assertEquals(201, out.length)
    }

    @Test
    fun `a body that is not Proton's JSON gives nothing`() {
        for (body in listOf("<html>502 Bad Gateway</html>", "no body", "", "   ", null)) {
            assertEquals("for $body", "", ProtonApiError.describe(body))
        }
    }

    @Test
    fun `JSON without Code or Error gives nothing`() {
        assertEquals("", ProtonApiError.describe("""{"Foo":1}"""))
        assertNull(ProtonApiError.parse("""{"Foo":1}"""))
    }

    @Test
    fun `a Code without an Error`() {
        assertEquals(" (Code 2501)", ProtonApiError.describe("""{"Code":2501}"""))
    }

    @Test
    fun `an Error without a Code`() {
        assertEquals(" (Error: Server busy)", ProtonApiError.describe("""{"Error":"Server busy"}"""))
    }

    /** Contact card writes: Proton's Error can quote the card, so those sites ask for the Code only. */
    @Test
    fun `withError false keeps the Code only`() {
        val body = """{"Code":8002,"Error":"Incorrect login credentials. Please try again"}"""
        assertEquals(" (Code 8002)", ProtonApiError.describe(body, withError = false))
        assertEquals("", ProtonApiError.describe("""{"Error":"only text"}""", withError = false))
    }

    /** Several call sites keep only the first 500 characters of a body; the Code must survive that. */
    @Test
    fun `a truncated body still yields its Code`() {
        val full = """{"Code":8002,"Error":"Incorrect login credentials. Please try again"}"""
        val cut = full.take(30)
        assertEquals(8002 to null, ProtonApiError.parse(cut))
        assertEquals(" (Code 8002)", ProtonApiError.describe(cut))
    }

    @Test
    fun `a truncated body keeps a complete Error, unescaped`() {
        val cut = """{"Code":2001,"Error":"Say \"hi\" to it","Details":{"Fi"""
        assertEquals(" (Code 2001: Say \"hi\" to it)", ProtonApiError.describe(cut))
    }

    @Test
    fun `spaces around the colon are read`() {
        assertEquals(33103 to null, ProtonApiError.parse("""{"Code" : 33103}"""))
        assertEquals(33103 to null, ProtonApiError.parse("""{"Code" : 33103, "Err"""))
    }

    @Test
    fun `format matches describe for the same values`() {
        val body = """{"Code":2000,"Error":"Server busy"}"""
        assertEquals(ProtonApiError.describe(body), ProtonApiError.format(2000, "Server busy"))
        assertEquals(" (Code 2000)", ProtonApiError.format(2000, null))
        assertEquals(" (Code 2000)", ProtonApiError.format(2000, "   "))
        assertEquals("", ProtonApiError.format(null, null))
    }

    @Test
    fun `a Code of the wrong type is not trusted`() {
        assertEquals(" (Error: x)", ProtonApiError.describe("""{"Code":"8002","Error":"x"}"""))
    }

    @Test
    fun `clean leaves nothing for blank input`() {
        assertNull(ProtonApiError.clean(null))
        assertNull(ProtonApiError.clean("  \n "))
        assertNull(ProtonApiError.clean("<br/>"))
        assertTrue(ProtonApiError.clean("a@b")!!.contains("<address>"))
    }
}
