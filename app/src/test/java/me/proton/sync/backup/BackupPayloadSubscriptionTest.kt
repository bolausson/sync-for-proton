package me.proton.sync.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The direct version's subscription id in a settings backup: an optional member of the payload, so a
 * file without it (older versions, the Play version, settings-only backups) reads exactly as before.
 */
class BackupPayloadSubscriptionTest {

    @Test
    fun `a subscription id round-trips`() {
        val payload = BackupPayload(mapOf("verbose_logging_enabled" to true), emptyList(), subscriptionId = "sub_01abc")
        val json = payload.toJson()
        assertEquals("sub_01abc", json.getJSONObject("subscription").getString("paddle_subscription_id"))
        assertEquals(payload, BackupPayload.fromJson(JSONObject(json.toString())))
    }

    @Test
    fun `no subscription id writes no member`() {
        val json = BackupPayload(emptyMap(), emptyList()).toJson()
        assertFalse(json.has("subscription"))
        assertNull(BackupPayload.fromJson(json).subscriptionId)
    }

    @Test
    fun `a file from before the member, or with a blank id, has none`() {
        assertNull(BackupPayload.fromJson(JSONObject("""{"settings":{},"accounts":[]}""")).subscriptionId)
        assertNull(
            BackupPayload.fromJson(
                JSONObject("""{"settings":{},"accounts":[],"subscription":{"paddle_subscription_id":"  "}}""")
            ).subscriptionId
        )
        assertNull(
            BackupPayload.fromJson(JSONObject("""{"settings":{},"accounts":[],"subscription":{}}""")).subscriptionId
        )
    }
}
