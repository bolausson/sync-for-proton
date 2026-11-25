package me.proton.sync.settings

import android.content.Context
import android.content.DialogInterface
import android.content.res.Configuration
import android.icu.lang.UCharacter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.StaticLayout
import android.text.TextPaint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.IllegalFormatException
import java.util.Locale
import me.proton.sync.Plurals
import me.proton.sync.R
import me.proton.sync.SyncLog

/**
 * Debug builds' dialog-titles verb (tools/debug/sync-debug.sh dialog-titles): every dialog title and every dialog button
 * label of the app, in every language of the app, measured on this phone. tools/debug/dialog_title_list.py reads them
 * out of the sources, and the verb pushes that list. For each language this logs what AppCompat's own limits cut,
 * reckoned from the views' fonts, sizes and widths the way AppCompat decides (a title: one line at the full size, else
 * at most two at the smaller one; a label: one line, in capitals), then shows every one in a real [AppDialogBuilder]
 * dialog and logs any that is still cut, or a title that wraps other than reckoned. Also the rule list's summary line
 * for every action, direction and match: how many lines it takes. Logs string names and counts only.
 */
internal object DialogTitleDebug {
    private const val TAG = "DebugCommand"
    /** Stands in for a calendar name in a label such as "Move to %1$s". */
    private const val SAMPLE_NAME = "Family calendar"
    private const val TIMEOUT_MS = 3_000L
    /** Counts that reach every plural form of the app's languages at values the app can show. */
    private val PLURAL_COUNTS = listOf(1, 2, 3, 5, 7, 11, 20, 22, 101)

    private val handler = Handler(Looper.getMainLooper())

    private enum class Kind { TITLE, BUTTON }

    /** One title or button label of the list, read in a given language (a context of that language, and the language). */
    private class Item(val kind: Kind, val name: String, val read: (Context, Locale) -> String)

    /** What a text view decides with: its width for the text, its sizes and its paint, read from a view of this phone. */
    private class Geometry(
        val width: Int,
        val fullSize: Float,
        val smallSize: Float,
        private val paint: TextPaint,
        private val breakStrategy: Int,
        private val hyphenation: Int,
        private val includePad: Boolean,
        private val spacingAdd: Float,
        private val spacingMult: Float,
    ) {
        fun lines(text: String, size: Float, locale: Locale, width: Int = this.width): Int {
            if (text.isEmpty()) return 1
            val p = TextPaint(paint).apply { textSize = size; textLocale = locale }
            return StaticLayout.Builder.obtain(text, 0, text.length, p, width)
                .setBreakStrategy(breakStrategy)
                .setHyphenationFrequency(hyphenation)
                .setIncludePad(includePad)
                .setLineSpacing(spacingAdd, spacingMult)
                .build()
                .lineCount
        }

        /** The width [text] takes on one line at the full size. */
        fun oneLine(text: String, locale: Locale): Float = TextPaint(paint).apply { textSize = fullSize; textLocale = locale }.measureText(text)

        /** The lines AppDialogBuilder shows for a title: one at the full size, else as many as the smaller size needs. */
        fun shownLines(text: String, locale: Locale): Int =
            if (lines(text, fullSize, locale) <= 1) 1 else lines(text, smallSize, locale)

        companion object {
            fun of(view: TextView, smallSize: Float, width: Int = view.width - view.totalPaddingLeft - view.totalPaddingRight) = Geometry(
                width = width,
                fullSize = view.textSize,
                smallSize = smallSize,
                paint = TextPaint(view.paint),
                breakStrategy = view.breakStrategy,
                hyphenation = view.hyphenationFrequency,
                includePad = view.includeFontPadding,
                spacingAdd = view.lineSpacingExtra,
                spacingMult = view.lineSpacingMultiplier,
            )
        }
    }

    /** Opens one dialog with the title [titleName] (and the body [bodyName]) and leaves it open, for a screenshot. */
    fun show(activity: AppCompatActivity, titleName: String, bodyName: String?) {
        val res = activity.resources
        val titleId = res.getIdentifier(titleName, "string", activity.packageName)
        if (titleId == 0) {
            SyncLog.w(TAG, "dialog_titles: no string named $titleName")
            return
        }
        val bodyId = bodyName?.let { res.getIdentifier(it, "string", activity.packageName) } ?: 0
        val dialog = AppDialogBuilder(activity)
            .setTitle(titleId)
            .setPositiveButton(R.string.btn_info_dismiss, null)
            .apply { if (bodyId != 0) setMessage(bodyId) }
            .create()
        val title = dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
        dialog.show()
        if (title == null) {
            SyncLog.w(TAG, "dialog_titles: showing $titleName, but the dialog has no title view")
            return
        }
        var logged = false
        val shownAs = "dialog_titles: showing $titleName${if (bodyId != 0) " with $bodyName" else ""}"
        val fallback = Runnable {
            if (logged) return@Runnable
            logged = true
            SyncLog.w(TAG, "$shownAs: " + if (AppDialogBuilder.isCut(title)) "STILL CUT, ${title.layout?.lineCount} line(s)" else "not laid out within $TIMEOUT_MS ms")
        }
        whenSettled(title, waitForWhole = true) {
            if (logged) return@whenSettled
            logged = true
            handler.removeCallbacks(fallback)
            SyncLog.i(TAG, "$shownAs: ${title.layout.lineCount} line(s), whole")
        }
        handler.postDelayed(fallback, TIMEOUT_MS)
    }

    /** Reads the list [file] and measures every title and label in every language it names; see the class comment. */
    fun run(activity: AppCompatActivity, file: File) {
        val lines = try {
            file.readLines()
        } catch (e: Exception) {
            SyncLog.w(TAG, "dialog_titles: cannot read ${file.name}: ${e.javaClass.simpleName}")
            return
        }
        val res = activity.resources
        fun id(name: String, type: String): Int = res.getIdentifier(name, type, activity.packageName)
        val locales = mutableListOf<Locale>()
        val items = mutableListOf<Item>()
        val summaries = mutableListOf<List<Int>>()
        val unknown = mutableListOf<String>()
        for (raw in lines) {
            val f = raw.trim().split(Regex("\\s+"))
            if (f.size < 2 || f[0].startsWith("#")) continue
            when (f[0]) {
                "locale" -> locales += Locale.forLanguageTag(f[1])
                "string", "button" -> {
                    val kind = if (f[0] == "button") Kind.BUTTON else Kind.TITLE
                    val s = id(f[1], "string")
                    // Names after the first are the texts the app puts into its placeholder, one item each.
                    val args = f.drop(2).map { it to id(it, "string") }
                    when {
                        s == 0 -> unknown += f[1]
                        args.any { it.second == 0 } -> unknown += args.filter { it.second == 0 }.joinToString(", ") { it.first }
                        args.isEmpty() -> items += Item(kind, f[1]) { c, l -> format(c.getString(s), l) }
                        else -> for ((argName, a) in args) items += Item(kind, "${f[1]}($argName)") { c, l -> c.getString(s, c.getString(a)) }
                    }
                }
                "plural" -> {
                    val s = id(f[1], "plurals")
                    // Each title plural takes its count alone (PluralFormsTest: every plural goes through Plurals).
                    if (s == 0) unknown += f[1]
                    else for (n in PLURAL_COUNTS) items += Item(Kind.TITLE, "${f[1]}[$n]") { c, _ -> Plurals.text(c, s, n, n) }
                }
                "concat" -> {
                    val a = id(f[1], "string")
                    val b = if (f.size > 2) id(f[2], "string") else 0
                    if (a == 0 || b == 0) unknown += f.drop(1).joinToString("+")
                    else items += Item(Kind.TITLE, "${f[1]}+${f[2]}") { c, l -> format(c.getString(a), l) + c.getString(b) }
                }
                "summary" -> {
                    val parts = f.drop(1).map { id(it, "string") }
                    if (parts.any { it == 0 }) unknown += f.drop(1).joinToString("+") else summaries += parts
                }
            }
        }
        if (unknown.isNotEmpty()) SyncLog.w(TAG, "dialog_titles: names this build does not have: ${unknown.joinToString(", ")}")
        if (locales.isEmpty() || items.isEmpty()) {
            SyncLog.w(TAG, "dialog_titles: the list names ${locales.size} language(s) and ${items.size} text(s); nothing to do")
            return
        }
        // A probe dialog gives the title view's and the button's widths and sizes on this phone.
        val probe = AppDialogBuilder(activity).setTitle("M").setMessage("M").setPositiveButton("M", null).create()
        val probeTitle = probe.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
        val probeButton = probe.getButton(DialogInterface.BUTTON_POSITIVE)
        if (probeTitle == null || probeButton == null) {
            SyncLog.w(TAG, "dialog_titles: the dialog has no title view or no button")
            return
        }
        probe.show()
        whenSettled(probeButton, waitForWhole = false) {
            val titleGeometry = Geometry.of(probeTitle, smallSize(probeTitle))
            val bar = probeButton.parent as? View
            // A stacked button spans the button bar, up to its style's maxWidth (-1 when it has none).
            val maxWidth = probeButton.maxWidth.takeIf { it > 0 } ?: Int.MAX_VALUE
            val stacked = minOf(bar?.let { it.width - it.paddingLeft - it.paddingRight } ?: probeButton.width, maxWidth)
            val buttonGeometry = Geometry.of(probeButton, probeButton.textSize, stacked - probeButton.totalPaddingLeft - probeButton.totalPaddingRight)
            dismissSafely(activity, probe)
            SyncLog.i(TAG, "dialog_titles: ${items.count { it.kind == Kind.TITLE }} titles, ${items.count { it.kind == Kind.BUTTON }} " +
                "button labels, ${locales.size} languages, ${summaries.size} summary lines; title ${titleGeometry.width} px wide, " +
                "${titleGeometry.fullSize} px, ${titleGeometry.smallSize} px when longer than a line; a stacked button's label " +
                "${buttonGeometry.width} px, ${buttonGeometry.fullSize} px")
            checkSummaries(activity, locales, summaries)
            Pass(activity, items, locales, titleGeometry, buttonGeometry).start()
        }
    }

    /** The size DialogTitle switches to for a title longer than a line: the theme's textAppearanceMedium. */
    private fun smallSize(title: TextView): Float {
        val a = title.context.obtainStyledAttributes(
            null, intArrayOf(android.R.attr.textSize), android.R.attr.textAppearanceMedium, android.R.style.TextAppearance_Medium,
        )
        val size = a.getDimensionPixelSize(0, 0).toFloat()
        a.recycle()
        return if (size > 0f) size else title.textSize
    }

    /** The rule list's summary line ("<action> · <direction>", and " · <match>" with two conditions or more) in every language. */
    private fun checkSummaries(activity: AppCompatActivity, locales: List<Locale>, summaries: List<List<Int>>) {
        if (summaries.isEmpty()) return
        val width = activity.findViewById<View>(android.R.id.content)?.width ?: 0
        if (width <= 0) {
            SyncLog.w(TAG, "dialog_titles: summary lines not checked: the screen has no width yet")
            return
        }
        val row = LayoutInflater.from(activity).inflate(R.layout.item_contact_filter_rule, FrameLayout(activity), false)
        row.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
        val summary = row.findViewById<TextView>(R.id.text_summary)
        val geometry = Geometry.of(summary, summary.textSize)
        var longest = 0
        val overTwo = mutableListOf<String>()
        for (locale in locales) {
            val context = contextFor(activity, locale)
            for (parts in summaries) {
                val text = parts.joinToString(" · ") { context.getString(it) }
                val n = geometry.lines(text, geometry.fullSize, locale)
                longest = maxOf(longest, n)
                if (n > 2) overTwo += "${locale.toLanguageTag()} ${parts.joinToString("+") { activity.resources.getResourceEntryName(it) }} ($n lines)"
            }
        }
        SyncLog.i(TAG, "dialog_titles: rule summary line (${geometry.width} px): at most $longest line(s), shown whole; " +
            if (overTwo.isEmpty()) "every one fits two lines" else "longer than the two lines it had before: ${overTwo.joinToString(", ")}")
    }

    /** Every title and label in a real AppDialogBuilder dialog, language by language, one dialog at a time. */
    private class Pass(
        private val activity: AppCompatActivity,
        private val items: List<Item>,
        private val locales: List<Locale>,
        private val titleGeometry: Geometry,
        private val buttonGeometry: Geometry,
    ) {
        private val started = SystemClock.elapsedRealtime()
        private var localeIndex = 0
        private var itemIndex = 0
        private var texts: List<String> = emptyList()
        /** Per item: the reckoned lines of a title; for a label, whether AppCompat's one line cut it (1) or not (0). */
        private var reckoned: List<Int> = emptyList()
        private var longestTitle = 0
        private var longestLabel = 0
        private val problems = mutableListOf<String>()
        private var cutBefore = 0
        private val languagesCut = mutableListOf<String>()
        private var problemsTotal = 0

        fun start() = nextLocale()

        private fun nextLocale() {
            if (localeIndex >= locales.size) return finish()
            val locale = locales[localeIndex]
            val context = contextFor(activity, locale)
            texts = items.map { item -> try { item.read(context, locale) } catch (e: Exception) { "" } }
            reckoned = items.indices.map { i ->
                when (items[i].kind) {
                    Kind.TITLE -> titleGeometry.shownLines(texts[i], locale)
                    Kind.BUTTON -> if (buttonGeometry.oneLine(UCharacter.toUpperCase(locale, texts[i]), locale) > buttonGeometry.width) 1 else 0
                }
            }
            longestTitle = 0
            longestLabel = 0
            problems.clear()
            itemIndex = 0
            nextItem()
        }

        private fun nextItem() {
            if (activity.isFinishing || activity.isDestroyed) {
                SyncLog.w(TAG, "dialog_titles: stopped, the screen closed")
                return
            }
            if (itemIndex >= items.size) {
                logLocale()
                localeIndex++
                handler.post { nextLocale() }
                return
            }
            val index = itemIndex
            val item = items[index]
            val builder = AppDialogBuilder(activity).setMessage("M")
            val dialog = if (item.kind == Kind.TITLE) builder.setTitle(texts[index]).create()
            else builder.setTitle("M").setPositiveButton(texts[index], null).create()
            val view: TextView? = if (item.kind == Kind.TITLE) dialog.findViewById(androidx.appcompat.R.id.alertTitle)
            else dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            if (view == null) {
                problems += "${item.name}: no view to measure"
                itemIndex++
                handler.post { nextItem() }
                return
            }
            view.textLocale = locales[localeIndex]
            var settled = false
            val timeout = Runnable {
                if (settled) return@Runnable
                settled = true
                problems += "${item.name}: ${if (AppDialogBuilder.isCut(view)) "STILL CUT" else "not laid out within $TIMEOUT_MS ms"}"
                dismissSafely(activity, dialog)
                itemIndex++
                handler.post { nextItem() }
            }
            whenSettled(view, waitForWhole = item.kind == Kind.TITLE) {
                if (settled) return@whenSettled
                settled = true
                handler.removeCallbacks(timeout)
                val layout = view.layout
                val shown = layout.lineCount
                if (item.kind == Kind.TITLE) {
                    longestTitle = maxOf(longestTitle, shown)
                    if (shown != reckoned[index]) problems += "${item.name}: $shown lines, reckoned ${reckoned[index]}"
                } else {
                    longestLabel = maxOf(longestLabel, shown)
                    val ellipsized = (0 until shown).any { layout.getEllipsisCount(it) > 0 }
                    val clipped = layout.height > view.height - view.compoundPaddingTop - view.compoundPaddingBottom
                    if (ellipsized || clipped) problems += "${item.name}: label ${if (ellipsized) "STILL CUT" else "clipped"} ($shown lines)"
                }
                dismissSafely(activity, dialog)
                itemIndex++
                handler.post { nextItem() }
            }
            handler.postDelayed(timeout, TIMEOUT_MS)
            dialog.show()
        }

        private fun logLocale() {
            val tag = locales[localeIndex].toLanguageTag()
            val cut = items.indices.filter { i -> if (items[i].kind == Kind.TITLE) reckoned[i] > 2 else reckoned[i] == 1 }
            cutBefore += cut.size
            if (cut.isNotEmpty()) languagesCut += "$tag ${cut.size}"
            problemsTotal += problems.size
            SyncLog.i(TAG, "dialog_titles: $tag: ${items.size} texts, AppCompat's limits cut ${cut.size}; " +
                "AppDialogBuilder shows every one ${if (problems.isEmpty()) "whole, the titles as reckoned" else "with ${problems.size} problem(s)"}, " +
                "longest title $longestTitle lines, longest label $longestLabel")
            for (i in cut) {
                val what = if (items[i].kind == Kind.TITLE) "title ${items[i].name} (${reckoned[i]} lines now)" else "label ${items[i].name}"
                SyncLog.i(TAG, "dialog_titles: $tag: cut before: $what")
            }
            for (p in problems) SyncLog.w(TAG, "dialog_titles: $tag: $p")
        }

        private fun finish() {
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000
            SyncLog.i(TAG, "dialog_titles: done in $seconds s: ${items.size} texts in ${locales.size} languages; AppCompat's " +
                "limits cut $cutBefore${if (languagesCut.isEmpty()) "" else " (${languagesCut.joinToString(", ")})"}; " +
                "AppDialogBuilder: ${if (problemsTotal == 0) "every title and label whole, every title as reckoned" else "$problemsTotal problem(s), see above"}")
        }
    }

    /**
     * Calls [done] once [view] is laid out, just before that frame is drawn; with [waitForWhole], only once it is not cut
     * (a title AppDialogBuilder still has to unclip).
     */
    private fun whenSettled(view: TextView, waitForWhole: Boolean, done: () -> Unit) {
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (view.layout == null || view.isLayoutRequested || (waitForWhole && AppDialogBuilder.isCut(view))) return true
                view.viewTreeObserver.removeOnPreDrawListener(this)
                handler.post(done)
                return true
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                v.viewTreeObserver.addOnPreDrawListener(listener)
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    /** Dismisses [dialog] only while its window can still be removed: not after the screen was destroyed. */
    private fun dismissSafely(activity: AppCompatActivity, dialog: AlertDialog) {
        if (!activity.isDestroyed && dialog.isShowing) dialog.dismiss()
    }

    private fun contextFor(activity: AppCompatActivity, locale: Locale): Context {
        val config = Configuration(activity.resources.configuration)
        config.setLocale(locale)
        return activity.createConfigurationContext(config)
    }

    /** A text with a sample name, else a sample count, where it has a placeholder. */
    private fun format(raw: String, locale: Locale): String {
        if (!raw.contains('%')) return raw
        for (arg in listOf<Any>(SAMPLE_NAME, 2)) {
            try {
                return String.format(locale, raw, arg)
            } catch (e: IllegalFormatException) {
                // the next kind of argument
            }
        }
        return raw
    }
}
