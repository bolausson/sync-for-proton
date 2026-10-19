package me.proton.sync.settings

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BulletSpan
import android.text.style.LeadingMarginSpan
import android.text.style.ReplacementSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityViewCommand
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.sync.BuildConfig
import me.proton.sync.R
import me.proton.sync.SyncLog
import me.proton.sync.databinding.ActivityLicencesBinding
import me.proton.sync.databinding.ItemLicenceCodeBinding
import me.proton.sync.databinding.ItemLicenceHeadingBinding
import me.proton.sync.databinding.ItemLicenceRowBinding
import me.proton.sync.databinding.ItemLicenceTextBinding
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The third-party licences: THIRD-PARTY-NOTICES.md, the notice the published source carries, as the build bundled it.
 *
 * The file is typeset, not rewritten. Its own words sit on the page; its tables are settings-style cards, a library
 * with its version and licence; its NOTICE files and licence texts are monospace on sheets of their own, line for line.
 * Those texts are wrapped for 80 columns and a phone shows about 40, so a line that does not fit continues under its
 * own text, or under the text after its list marker, the way the texts indent their own wrapped lines. On a wide
 * screen the column stops at [COLUMN_CHARS] monospace characters, and nearly every line then shows exactly as laid
 * out.
 *
 * The direct build leaves out the table rows marked "play only" ([ThirdPartyNotices.withoutPlayOnly]): that APK holds
 * no Google Play code.
 */
class LicencesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLicencesBinding
    private val adapter = NoticeAdapter()
    private val surface by lazy { ContextCompat.getColor(this, R.color.sfp_surface) }

    /** The reading column at its widest, in pixels: [COLUMN_CHARS] monospace characters and a sheet's padding. */
    private var maxColumn = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityLicencesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        // The rows arrive after the first layout, so the scroll position of a rotation waits for them.
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        binding.noticeList.layoutManager = LinearLayoutManager(this)
        binding.noticeList.adapter = adapter
        binding.noticeList.itemAnimator = null
        binding.noticeList.setHasFixedSize(true)
        binding.noticeList.addItemDecoration(Gaps())
        capColumn()

        // About 52 KB of Markdown, read and parsed off the main thread.
        lifecycleScope.launch {
            val rows = try {
                withContext(Dispatchers.IO) { loadRows() }
            } catch (e: IOException) {
                // A built APK always has the file (the build fails without it): leave rather than show an empty page.
                SyncLog.w(TAG, "The third-party notice could not be read: ${e.message}")
                finish()
                return@launch
            }
            adapter.show(rows)
        }
    }

    private fun loadRows(): List<ThirdPartyNotices.Row> {
        val markdown = assets.open(NOTICES_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        val notices = ThirdPartyNotices.parse(markdown)
        return (if (BuildConfig.FLAVOR == "direct") notices.withoutPlayOnly() else notices).rows()
    }

    /**
     * Caps the reading column at [COLUMN_CHARS] monospace characters on a wide screen, centred: a line length people
     * can read, and a licence sheet as wide as the texts were wrapped for. A phone keeps the usual screen margin.
     */
    private fun capColumn() {
        val list = binding.noticeList
        val mono = TextPaint().apply {
            typeface = Typeface.MONOSPACE
            textSize = resources.getDimension(R.dimen.sfp_text_row_sub)
        }
        val sheetPadding = resources.getDimensionPixelSize(R.dimen.sfp_row_pad_h)
        maxColumn = ceil(COLUMN_CHARS * mono.measureText(" ")).toInt() + 2 * sheetPadding + 1
        val margin = resources.getDimensionPixelSize(R.dimen.sfp_screen_h)
        list.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
            val side = maxOf(margin, (right - left - maxColumn) / 2)
            // Posted: padding changed inside a layout pass would ask for a second pass of that same layout.
            if (side != list.paddingStart) {
                list.post { list.setPaddingRelative(side, list.paddingTop, side, list.paddingBottom) }
            }
        }
    }

    /** Level 1, the file's title, is large but regular: the start of the text, not a second title under the toolbar. */
    private fun bindHeading(view: TextView, row: ThirdPartyNotices.Heading) {
        view.text = styled(row.text)
        val size = when (row.level) {
            1 -> R.dimen.sfp_text_hero_title
            2 -> R.dimen.sfp_text_heading
            3 -> R.dimen.sfp_text_row_title
            else -> R.dimen.sfp_text_row_value
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(size))
        view.setTypeface(null, if (row.level == 1) Typeface.NORMAL else Typeface.BOLD)
        // Level 4 names one NOTICE file: in the secondary colour it reads as under "NOTICE files", its level 3.
        val colour = if (row.level >= 4) R.color.sfp_text_secondary else R.color.sfp_text
        view.setTextColor(ContextCompat.getColor(this, colour))
    }

    private fun bindText(view: TextView, text: ThirdPartyNotices.Inline, bullet: Boolean) {
        val styled = styled(text)
        if (bullet) {
            val dot = BulletSpan(dp(10), view.currentTextColor, dp(2))
            styled.setSpan(dot, 0, styled.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        view.text = styled
        // Only where there is a link: the movement method makes the whole paragraph something to activate.
        val links = text.marks.filterIsInstance<ThirdPartyNotices.LinkMark>()
        view.movementMethod = if (links.isNotEmpty()) LinkMovementMethod.getInstance() else null
        // TalkBack offers a double-tap on such a paragraph, which on its own opens nothing: with one link, it opens
        // that link (a paragraph with several keeps TalkBack's links menu). Touch is left alone. A null command
        // removes the replacement when the holder is bound to other text.
        val only = links.singleOrNull()
        ViewCompat.replaceAccessibilityAction(
            view, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, null,
            only?.let { link -> AccessibilityViewCommand { v, _ -> LinkSpan(link.url).onClick(v); true } }
        )
    }

    private fun bindRow(b: ItemLicenceRowBinding, row: ThirdPartyNotices.TableRow) {
        b.root.background = cardSlice(R.dimen.sfp_radius_group, top = row.first, bottom = row.last)
        // By position, as the table orders them: name, version, licence, then anything more. The Build column apart.
        val plain = row.cells.filterIndexed { i, _ -> i != row.buildColumn }
        // An invisible break after each dot, colon and slash: a long coordinate wraps between its parts.
        b.textName.text = plain.getOrNull(0).orEmpty().replace(SEGMENT_END, "$0\u200B")
        b.textVersion.text = plain.getOrNull(1).orEmpty()
        b.textLicence.text = plain.getOrNull(2).orEmpty()
        val more = plain.drop(3).filter { it.isNotBlank() }.joinToString("\n")
        b.textMore.text = more
        b.textMore.isVisible = more.isNotEmpty()
        // Nearly every row is in both builds, so only the exception carries a label.
        val build = row.cells.getOrNull(row.buildColumn)
            ?.takeUnless { it.isBlank() || it.equals(ThirdPartyNotices.BOTH, ignoreCase = true) }
        b.textBuild.text = build
        b.textBuild.isVisible = build != null
        b.divider.isVisible = !row.last
        b.root.contentDescription = row.header.zip(row.cells) { name, value -> "$name: $value" }.joinToString(", ")
    }

    private fun bindCode(view: TextView, row: ThirdPartyNotices.CodeLines) {
        view.background = cardSlice(R.dimen.sfp_radius_field, top = row.first, bottom = row.last)
        val side = resources.getDimensionPixelSize(R.dimen.sfp_row_pad_h)
        view.setPaddingRelative(side, if (row.first) dp(16) else 0, side, if (row.last) dp(16) else 0)
        // Characters per sheet line: what the screen leaves inside the usual margins, COLUMN_CHARS at most, which is
        // what a capped column holds. It follows from the list's width alone, so a late padding change cannot skew it.
        val list = binding.noticeList
        val margin = resources.getDimensionPixelSize(R.dimen.sfp_screen_h)
        val columns = minOf(COLUMN_CHARS, ((list.width - 2 * margin - 2 * side) / view.paint.measureText(" ")).toInt())
        view.text = verbatim(row.text, view.paint, columns)
    }

    private fun styled(text: ThirdPartyNotices.Inline): SpannableString {
        val out = SpannableString(text.text)
        for (mark in text.marks) {
            val span: Any = when (mark) {
                is ThirdPartyNotices.CodeMark -> TypefaceSpan("monospace")
                is ThirdPartyNotices.LinkMark -> LinkSpan(mark.url)
            }
            out.setSpan(span, mark.start, mark.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    /**
     * A run of a licence text, every character as written; only how a line wraps, and how wide a centring indent is
     * drawn, are set here. A line too long for the sheet's [columns] continues at [ThirdPartyNotices.hangingIndent]:
     * under its own text, or under the text after its list marker, keeping at least [MIN_WRAP_COLUMNS] for the text
     * at the largest font sizes. Every line that [CENTRING_INDENT] or more leading spaces centre for 80 columns is
     * centred again for the columns there are, so a title's lines stay centred together: drawn as they stand, the
     * spaces alone would fill a phone's line and push the title onto the next. At 80 columns that is their own indent.
     */
    private fun verbatim(text: String, paint: TextPaint, columns: Int): CharSequence {
        val column = paint.measureText(" ")
        val out = SpannableString(text)
        var start = 0
        for (line in text.split('\n')) {
            val end = start + line.length
            val indent = line.length - line.trimStart(' ').length
            val hang = if (indent >= CENTRING_INDENT) {
                val centred = maxOf(0, (columns - (line.length - indent)) / 2)
                out.setSpan(IndentSpan(centred * column), start, start + indent, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                centred
            } else {
                minOf(ThirdPartyNotices.hangingIndent(line), maxOf(0, columns - MIN_WRAP_COLUMNS))
            }
            if (hang > 0) {
                // Over the line and its newline: that is the paragraph a leading margin applies to.
                val margin = LeadingMarginSpan.Standard(0, (hang * column).roundToInt())
                out.setSpan(margin, start, minOf(end + 1, text.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            start = end + 1
        }
        return out
    }

    /** One slice of a white card that runs over several rows: rounded only where the card starts and where it ends. */
    private fun cardSlice(radiusRes: Int, top: Boolean, bottom: Boolean): GradientDrawable {
        val radius = resources.getDimension(radiusRes)
        val t = if (top) radius else 0f
        val b = if (bottom) radius else 0f
        return GradientDrawable().apply {
            setColor(surface)
            cornerRadii = floatArrayOf(t, t, t, t, b, b, b, b)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private inner class NoticeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<ThirdPartyNotices.Row> = emptyList()
            private set

        fun show(rows: List<ThirdPartyNotices.Row>) {
            this.rows = rows
            notifyItemRangeInserted(0, rows.size)
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is ThirdPartyNotices.Heading -> TYPE_HEADING
            is ThirdPartyNotices.Paragraph, is ThirdPartyNotices.Bullet -> TYPE_TEXT
            is ThirdPartyNotices.TableRow -> TYPE_ROW
            is ThirdPartyNotices.CodeLines -> TYPE_CODE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_ROW -> RowHolder(ItemLicenceRowBinding.inflate(inflater, parent, false))
                TYPE_HEADING -> TextHolder(ItemLicenceHeadingBinding.inflate(inflater, parent, false).root)
                TYPE_CODE -> TextHolder(ItemLicenceCodeBinding.inflate(inflater, parent, false).root)
                else -> TextHolder(ItemLicenceTextBinding.inflate(inflater, parent, false).root)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is ThirdPartyNotices.Heading -> bindHeading((holder as TextHolder).text, row)
                is ThirdPartyNotices.Paragraph -> bindText((holder as TextHolder).text, row.text, bullet = false)
                is ThirdPartyNotices.Bullet -> bindText((holder as TextHolder).text, row.text, bullet = true)
                is ThirdPartyNotices.TableRow -> bindRow((holder as RowHolder).b, row)
                is ThirdPartyNotices.CodeLines -> bindCode((holder as TextHolder).text, row)
            }
        }
    }

    private class TextHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    private class RowHolder(val b: ItemLicenceRowBinding) : RecyclerView.ViewHolder(b.root)

    /** The space above each row: room before a heading by its level, none inside a card or a sheet. */
    private inner class Gaps : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val position = parent.getChildAdapterPosition(view)
            val row = adapter.rows.getOrNull(position) ?: return
            outRect.top = dp(gapBefore(adapter.rows.getOrNull(position - 1), row))
        }
    }

    /** A link that opens the browser, and does nothing where there is none, as the settings screen's links do. */
    private class LinkSpan(url: String) : URLSpan(url) {
        override fun onClick(widget: View) {
            try {
                widget.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) { /* no browser */ }
        }
    }

    /** Draws a run of spaces narrower than it is. The characters stay in the text; only their width changes. */
    private class IndentSpan(private val width: Float) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) paint.getFontMetricsInt(fm)
            return width.roundToInt()
        }

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
        ) = Unit
    }

    private companion object {
        const val TAG = "Licences"

        /** What the build writes into the assets: the root THIRD-PARTY-NOTICES.md, by BundleThirdPartyNotices. */
        const val NOTICES_ASSET = "THIRD-PARTY-NOTICES.md"

        const val TYPE_HEADING = 0
        const val TYPE_TEXT = 1
        const val TYPE_ROW = 2
        const val TYPE_CODE = 3

        /**
         * Leading spaces from this many on centre a line for 80 columns: the Apache License's title has 24 to 33,
         * while no indentation that structures a text in the notice goes past 10.
         */
        const val CENTRING_INDENT = 16

        /** The widest the reading column gets, in monospace characters: what the licence texts are wrapped for. */
        const val COLUMN_CHARS = 80

        /**
         * The fewest columns a wrapped continuation line keeps for its text, however deep its hanging indent: at the
         * largest font sizes a phone's sheet holds under 30, and a full hang left 9, splitting ordinary words.
         */
        const val MIN_WRAP_COLUMNS = 20

        /** Where a library's coordinate may break: after the dots, colons and slashes between its parts. */
        val SEGMENT_END = Regex("[.:/]")

        /** In dp. Headings get room by level and sit close to what they head; the rows of one card or sheet touch. */
        fun gapBefore(previous: ThirdPartyNotices.Row?, row: ThirdPartyNotices.Row): Int = when {
            previous == null -> 4
            row is ThirdPartyNotices.TableRow && !row.first -> 0
            row is ThirdPartyNotices.CodeLines && !row.first -> 0
            row is ThirdPartyNotices.Heading && previous is ThirdPartyNotices.Heading -> 10
            row is ThirdPartyNotices.Heading -> when (row.level) {
                1 -> 4
                2 -> 32
                3 -> 26
                else -> 20
            }
            previous is ThirdPartyNotices.Heading -> 8
            row is ThirdPartyNotices.Bullet && previous is ThirdPartyNotices.Bullet -> 4
            else -> 12
        }
    }
}
