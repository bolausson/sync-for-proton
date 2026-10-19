package me.proton.sync.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Recipient key lookup: who counts as a Proton user, and which failures are worth a warning.
 *
 * Both matter for the same reason. Misreading an external address as Proton sends it the wrong kind
 * of message; misreading a Proton user as external quietly drops end-to-end encryption to them.
 */
class PublicKeyLookupTest {

    private fun keysResponse(recipientType: Int?, vararg keys: String): Map<String, Any> {
        val body = HashMap<String, Any>()
        body["Code"] = 1000.0
        body["Keys"] = keys.map { mapOf("Flags" to 3.0, "PublicKey" to it) }
        // Gson decodes JSON numbers into Double inside a Map<String, Any>.
        if (recipientType != null) body["RecipientType"] = recipientType.toDouble()
        return body
    }

    @Test
    fun `a Proton user yields their key`() {
        assertEquals("PROTON-KEY", protonRecipientKey(keysResponse(1, "PROTON-KEY")))
    }

    /**
     * The defect: an external domain that publishes WKD keys returns RecipientType 2 WITH keys, and
     * "the first key wins" treated it as a Proton user.
     */
    @Test
    fun `an external address with WKD keys is not a Proton user`() {
        assertNull(protonRecipientKey(keysResponse(2, "WKD-KEY")))
    }

    @Test
    fun `an ordinary external address is not a Proton user`() {
        assertNull(protonRecipientKey(keysResponse(2)))
    }

    /** Without RecipientType, keep the old rule rather than risk stripping encryption from Proton users. */
    @Test
    fun `a response without RecipientType keeps the old rule`() {
        assertEquals("SOME-KEY", protonRecipientKey(keysResponse(null, "SOME-KEY")))
        assertNull(protonRecipientKey(keysResponse(null)))
    }

    @Test
    fun `a Proton user with no key is not treated as encryptable`() {
        assertNull(protonRecipientKey(keysResponse(1)))
    }

    @Test
    fun `a missing or unreadable body yields nothing`() {
        assertNull(protonRecipientKey(null))
        assertNull(protonRecipientKey(mapOf("Keys" to "not a list", "RecipientType" to 1.0)))
    }

    @Test
    fun `missing address and external domain are not failures`() {
        assertNull(unexpectedKeyLookupFailure(422, "{\"Code\":33102,\"Error\":\"Address does not exist\"}"))
        assertNull(unexpectedKeyLookupFailure(422, "{\"Code\" : 33103}"))
    }

    /** Proton's web client surfaces these as recipient errors, not as "external": keep them visible. */
    @Test
    fun `invalid input and key transparency failures are reported`() {
        assertEquals("HTTP 422, Proton code 33101", unexpectedKeyLookupFailure(422, "{\"Code\":33101}"))
        assertEquals("HTTP 422, Proton code 33104", unexpectedKeyLookupFailure(422, "{\"Code\":33104}"))
    }

    @Test
    fun `any other Proton code is reported with it`() {
        assertEquals(
            "HTTP 429, Proton code 2028",
            unexpectedKeyLookupFailure(429, "{\"Code\":2028,\"Error\":\"Too many requests\"}")
        )
    }

    @Test
    fun `a failure with no parseable code is still reported`() {
        assertEquals("HTTP 503", unexpectedKeyLookupFailure(503, null))
        assertEquals("HTTP 502", unexpectedKeyLookupFailure(502, "<html>Bad gateway</html>"))
    }

    /** Whole-number matching: a longer code that merely starts with 33102 is not the quiet answer. */
    @Test
    fun `a longer code is not mistaken for a quiet one`() {
        assertEquals("HTTP 422, Proton code 331020", unexpectedKeyLookupFailure(422, "{\"Code\":331020}"))
    }
}
