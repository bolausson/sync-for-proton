package me.proton.sync.settings

import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * The builder of every dialog of this app ([DialogBuilderSourceTest] keeps it so): AppCompat's own, except that neither
 * the title nor a button label is ever cut off.
 *
 * AppCompat's title view (DialogTitle, appcompat 1.7.0) shows one line. When that line is cut, it switches to a smaller
 * size and allows a second line, and cuts that one with "...". A title here is mostly the label of the row its (i) sits
 * next to, and in some languages that takes more than two lines: German showed "Auch sichern, wenn die automatische
 * Synchronisierung au..." (2026-11-23). So a title DialogTitle would cut keeps that smaller size and wraps onto as many
 * lines as it needs; a title that fits on one or two lines looks exactly as before.
 *
 * The buttons: the dialog theme (Material's TextButton.Dialog) gives them one line with "...", even when the button bar
 * stacks them, which cut long labels (Bulgarian "Don't remind me", Greek name repair, "Move to <calendar>"). Here a
 * label that does not fit wraps, still in capitals.
 */
class AppDialogBuilder(context: Context) : AlertDialog.Builder(context) {

    override fun create(): AlertDialog {
        val dialog = super.create()
        // The dialog's views are installed now instead of at show(), so the title and the buttons can be reached here.
        // Every setting of the builder is applied by then. A title, message or buttons set on the dialog itself after
        // create() would miss the views installed here, so the app sets none ([DialogBuilderSourceTest]): its one later
        // change is the text of a message that exists from the start.
        dialog.create()
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.let(::showWholeTitle)
        for (which in BUTTONS) dialog.getButton(which)?.let(::wrapLabel)
        return dialog
    }

    companion object {
        private val BUTTONS = intArrayOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL)

        /**
         * Lets [title] wrap where DialogTitle cut it: just before a frame is drawn, a title whose last line ends in "..."
         * drops its line limit and the "...", and that frame is skipped, so the next one shows the whole title in the
         * size DialogTitle chose. Added each time the title is attached, because a dialog shown again after a dismiss
         * gets a new window, with a new tree observer. A title DialogTitle has not measured yet (one replaced while the
         * dialog shows) leaves its one-line mode here too, so it wraps rather than being cut without "...".
         */
        internal fun showWholeTitle(title: TextView) {
            val unclip = ViewTreeObserver.OnPreDrawListener {
                if (!isCut(title)) return@OnPreDrawListener true
                title.isSingleLine = false
                title.maxLines = Int.MAX_VALUE
                title.ellipsize = null
                false
            }
            title.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = v.viewTreeObserver.addOnPreDrawListener(unclip)
                override fun onViewDetachedFromWindow(v: View) = v.viewTreeObserver.removeOnPreDrawListener(unclip)
            })
        }

        /**
         * Lets a button label wrap: no line limit, no "...", no horizontal scrolling. The style's capitals stay (its
         * transformation is left alone), and the button bar still stacks buttons that do not fit side by side, since each
         * one still measures its label on one line first.
         */
        internal fun wrapLabel(button: TextView) {
            button.setHorizontallyScrolling(false)
            button.maxLines = Int.MAX_VALUE
            button.ellipsize = null
        }

        /** Whether [view]'s last line ends in "...", which is how DialogTitle cuts a title. */
        internal fun isCut(view: TextView): Boolean {
            val layout = view.layout ?: return false
            val last = layout.lineCount - 1
            return last >= 0 && layout.getEllipsisCount(last) > 0
        }
    }
}
