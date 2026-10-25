package me.proton.sync.account

import me.proton.sync.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** The setup chooser promises "Start syncing" only when the subscription lets sync start. */
class SetupChooserLabelTest {

    @Test
    fun `an active subscription starts syncing`() {
        assertEquals(R.string.setup_choose_save, setupChooserSaveLabel(syncAllowed = true))
    }

    @Test
    fun `without one the button only continues`() {
        assertEquals(R.string.btn_continue, setupChooserSaveLabel(syncAllowed = false))
    }
}
