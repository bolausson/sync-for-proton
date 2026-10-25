package me.proton.sync.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.proton.sync.DebugLog
import me.proton.sync.LogBuffer
import me.proton.sync.LogRecord
import me.proton.sync.R
import me.proton.sync.SyncLog
import me.proton.sync.SyncLogTags
import me.proton.sync.SyncPreferences
import me.proton.sync.databinding.ActivityLogViewerBinding

/**
 * Reads the app log.
 *
 * Two ideas are kept strictly apart here, because conflating them is what made log reports
 * unusable. The **sync log** is what this screen draws: a filtered view, chosen with the
 * segmented control, for a person reading on a phone. The **debug log** is what every
 * button hands over: the complete capture from [DebugLog], identical whichever button is
 * pressed and regardless of what the view happens to be showing.
 *
 * The screen no longer reads `logcat`. It draws [LogBuffer], which every [SyncLog] call
 * feeds unconditionally. The old arrangement re-read logcat through a hardcoded tag
 * allowlist, so any tag missing from that list was invisible here *and* absent from every
 * report; 23 were missing, including the ones the sign-in and CAPTCHA screens use.
 */
class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding
    private val adapter = LogAdapter()
    private var autoScroll = true
    private val handler = Handler(Looper.getMainLooper())

    /** False = sync view (the default), true = every captured line. Display only. */
    private var showAll = false

    /** Highest sequence already drawn, so the live listener never re-adds after a rebuild. */
    private var lastSeq = 0L

    /** Lines matching the current view, and lines captured since the last clear. */
    private var shownCount = 0
    private var totalCount = 0

    private val logListener: (LogRecord) -> Unit = { record ->
        handler.post { onNewRecord(record) }
    }

    /**
     * Saves the debug log, not the view. Sharing to a mail app is the usual route, but some
     * clients drop the attachment, so this gives people a copy they can attach themselves.
     */
    private val saveLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        onBackground {
            DebugLog.writeTo(this, uri)
            handler.post {
                Toast.makeText(this, getString(R.string.toast_log_saved), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        const val TAG = "LogViewer"
        const val COLOR_ERROR = 0xFFFF6B6B.toInt()
        const val COLOR_WARN = 0xFFFFBB33.toInt()
        const val COLOR_INFO = 0xFF88CC88.toInt()
        const val COLOR_DEBUG = 0xFFAAAACC.toInt()

        /**
         * Process-scoped, so leaving and re-entering the screen does not resurrect lines the
         * user cleared away. Clearing hides them from the *view* only; the debug log keeps
         * every line, which is why this is a timestamp rather than a delete.
         */
        var clearedAtMs = 0L
    }

    /**
     * Mirrors the Settings toggle, because Settings needs an account and this screen does not: a
     * user stuck at sign-in could otherwise never raise the log level, which is precisely when the
     * extra detail is worth having.
     *
     * Takes effect immediately. [SyncLog.setVerboseEnabled] flips a volatile flag read on every
     * call site, so the next line logged honours the new level — no restart, and no need to leave
     * and re-enter this screen. Lines already captured were emitted at the old level and do not
     * change retroactively, so reproduce the problem again after turning it on.
     */
    private fun setupVerboseLoggingToggle() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        binding.switchVerboseLogging.isChecked = prefs.getBoolean(
            SyncPreferences.KEY_VERBOSE_LOGGING,
            SyncPreferences.DEFAULT_VERBOSE_LOGGING
        )
        binding.switchVerboseLogging.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(SyncPreferences.KEY_VERBOSE_LOGGING, checked).apply()
            SyncLog.setVerboseEnabled(checked)
            // Logged at INFO so it is always visible, marking exactly where the level changed.
            SyncLog.i(TAG, "Verbose logging ${if (checked) "enabled" else "disabled"} from log viewer")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        val layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.logRecycler.layoutManager = layoutManager
        binding.logRecycler.adapter = adapter

        // Detect manual scroll up → disable auto-scroll
        binding.logRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && recyclerView.canScrollVertically(1)) {
                    autoScroll = false
                    updateAutoScrollButton()
                }
            }
        })

        binding.btnAutoScroll.setOnClickListener {
            autoScroll = !autoScroll
            updateAutoScrollButton()
            if (autoScroll && adapter.itemCount > 0) {
                binding.logRecycler.scrollToPosition(adapter.itemCount - 1)
            }
        }
        updateAutoScrollButton()
        setupVerboseLoggingToggle()
        setupFilter()

        // Clearing hides, it does not delete: the shared debug log still carries every line,
        // and the marker below lands after the cut so the reader can see where it happened.
        binding.btnClear.setOnClickListener {
            clearedAtMs = System.currentTimeMillis()
            rebuild()
            SyncLog.i(TAG, "Log view cleared (the debug log still holds every line)")
        }

        binding.btnCopy.setOnClickListener {
            onBackground {
                val text = DebugLog.build(this)
                handler.post {
                    // The clipboard rides a binder transaction with a hard size limit, and a
                    // long debug log can reach it. Failing here must point at the share and
                    // save buttons, which go through a file and have no such ceiling.
                    try {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(getString(R.string.title_sync_logs), text)
                        )
                        Toast.makeText(this, getString(R.string.toast_logs_copied), Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                        Toast.makeText(this, getString(R.string.toast_log_too_large_to_copy), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        binding.btnSave.setOnClickListener { saveLogLauncher.launch(DebugLog.fileName()) }

        binding.btnExport.setOnClickListener {
            onBackground {
                // No subject: shareIntent falls back to the name of the file it just wrote,
                // so the subject line and the attachment always agree.
                val intent = DebugLog.shareIntent(this)
                handler.post {
                    startActivity(
                        Intent.createChooser(intent, getString(R.string.label_export_logs_chooser))
                    )
                }
            }
        }

        rebuild()
        LogBuffer.addListener(logListener)
    }

    override fun onDestroy() {
        LogBuffer.removeListener(logListener)
        super.onDestroy()
    }

    private fun setupFilter() {
        // Mirrors the bindInfo pattern the settings screen uses for its rows. Worth having
        // here because the control looks like it might change what gets recorded or sent,
        // and it changes neither.
        binding.infoLogFilter.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.label_log_filter_title)
                .setMessage(R.string.info_log_filter_body)
                .setPositiveButton(R.string.btn_info_dismiss, null)
                .show()
        }

        binding.groupLogFilter.check(if (showAll) R.id.btn_filter_all else R.id.btn_filter_sync)
        binding.groupLogFilter.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val next = checkedId == R.id.btn_filter_all
            if (next == showAll) return@addOnButtonCheckedListener
            showAll = next
            rebuild()
        }
    }

    private fun visible(record: LogRecord): Boolean =
        showAll || SyncLogTags.isSyncLine(record)

    /** Redraw from the buffer. Used on open and whenever the lens changes. */
    private fun rebuild() {
        val records = LogBuffer.snapshot().filter { it.timeMs >= clearedAtMs }
        totalCount = records.size
        lastSeq = records.lastOrNull()?.seq ?: 0L
        val visible = records.filter { visible(it) }
        shownCount = visible.size
        adapter.setAll(visible)
        updateCount()
        if (autoScroll && adapter.itemCount > 0) {
            binding.logRecycler.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun onNewRecord(record: LogRecord) {
        // Already drawn by a rebuild that ran between append and this callback.
        if (record.seq <= lastSeq) return
        lastSeq = record.seq
        if (record.timeMs < clearedAtMs) return
        totalCount++
        if (visible(record)) {
            shownCount++
            adapter.addLine(record)
            if (autoScroll) binding.logRecycler.scrollToPosition(adapter.itemCount - 1)
        }
        updateCount()
    }

    private fun updateCount() {
        binding.textLogCount.text = getString(R.string.label_log_count, shownCount, totalCount)
    }

    private fun updateAutoScrollButton() {
        // Keep the label static ("Auto-scroll") and show state via the icon + opacity.
        // This avoids long translations being clipped in the bottom bar.
        binding.btnAutoScroll.alpha = if (autoScroll) 1.0f else 0.5f
    }

    /**
     * Building the debug log copies the in-memory buffer and shells out to `logcat`, and saving
     * or sharing it then writes a file, none of which belongs on the main thread.
     */
    private fun onBackground(work: () -> Unit) {
        Thread {
            try {
                work()
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_export_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // --- RecyclerView Adapter ---

    private class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {
        private val records = mutableListOf<LogRecord>()

        class VH(val textView: TextView) : RecyclerView.ViewHolder(textView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 11f
                setTextIsSelectable(true)
                setPadding(16, 2, 16, 2)
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val record = records[position]
            holder.textView.text = LogBuffer.format(record)
            holder.textView.setTextColor(colorForLevel(record.level))
        }

        override fun getItemCount() = records.size

        // Fix textIsSelectable bug on Android 6.0+ in RecyclerView
        override fun onViewAttachedToWindow(holder: VH) {
            super.onViewAttachedToWindow(holder)
            holder.textView.isEnabled = false
            holder.textView.isEnabled = true
        }

        fun addLine(record: LogRecord) {
            records.add(record)
            notifyItemInserted(records.size - 1)
        }

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun setAll(newRecords: List<LogRecord>) {
            records.clear()
            records.addAll(newRecords)
            notifyDataSetChanged()
        }

        /**
         * Colour by the record's own level. The previous version substring-matched the
         * rendered line for " E " and " W/", which mis-coloured any message that happened to
         * contain them.
         */
        private fun colorForLevel(level: Char): Int = when (level) {
            'E' -> COLOR_ERROR
            'W' -> COLOR_WARN
            'I' -> COLOR_INFO
            else -> COLOR_DEBUG
        }
    }
}
