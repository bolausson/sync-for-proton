package me.proton.sync.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import me.proton.sync.R
import me.proton.sync.SyncLog
import me.proton.sync.contacts.filter.Combinator
import me.proton.sync.contacts.filter.ContactFilterStore
import me.proton.sync.contacts.filter.FilterPresets
import me.proton.sync.contacts.filter.FilterRule
import me.proton.sync.contacts.filter.FilterRuleSet
import me.proton.sync.contacts.filter.RuleAction
import me.proton.sync.contacts.filter.RuleDirection
import me.proton.sync.databinding.ActivityContactFilterRulesBinding
import me.proton.sync.databinding.ItemContactFilterRuleBinding
import java.util.Collections
import java.util.UUID
import javax.inject.Inject

/**
 * The rules deciding which contacts reach this phone.
 *
 * Drawn as a decision chain rather than a list of toggles, because that is what it is: rules are
 * checked top to bottom and the first match decides. The position number and the permanent
 * "Everything else" row at the bottom exist to make that legible. Without the fall-through on
 * screen, a list of "sync if in group X" rules looks like it should already work and silently does
 * nothing, because the default is to sync everything.
 */
@AndroidEntryPoint
class ContactFilterRulesActivity : AppCompatActivity() {

    @Inject lateinit var syncDatabase: me.proton.sync.db.SyncDatabase

    private lateinit var binding: ActivityContactFilterRulesBinding
    private lateinit var accountName: String
    private lateinit var adapter: RuleAdapter

    private var ruleSet: FilterRuleSet = FilterRuleSet.EMPTY

    /**
     * Whether anything changed this visit.
     *
     * The revision is bumped once, in [onStop], not per edit. Every arrow tap bumping it would
     * force a full re-evaluation of the whole address book per tap.
     */
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityContactFilterRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        accountName = intent.getStringExtra(EXTRA_ACCOUNT_NAME).orEmpty()
        if (accountName.isEmpty()) {
            finish()
            return
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        ruleSet = ContactFilterStore.load(this, accountName)

        adapter = RuleAdapter()
        binding.rulesRecycler.layoutManager = LinearLayoutManager(this)
        binding.rulesRecycler.adapter = adapter

        // Drag to reorder. The move is applied to the backing list as the finger travels so the
        // list tracks the drag, but it is persisted once in clearView: writing on every onMove
        // rewrites the whole preference file dozens of times in a single drag.
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val rules = ruleSet.rules.toMutableList()
                Collections.swap(rules, from, to)
                ruleSet = ruleSet.copy(rules = rules)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                markDirtyAndSave()
                adapter.notifyDataSetChanged()
            }
        })
        touchHelper.attachToRecyclerView(binding.rulesRecycler)
        adapter.dragStarter = { vh -> touchHelper.startDrag(vh) }

        binding.rowFallthrough.setOnClickListener { chooseFallthrough() }
        binding.rowPreview.setOnClickListener {
            ContactFilterPreviewUi.run(this, syncDatabase, accountName, ruleSet)
        }
        binding.btnAddRule.setOnClickListener { addRule() }
        binding.btnTemplates.setOnClickListener { chooseTemplate() }

        render()
    }

    override fun onResume() {
        super.onResume()
        // The editor writes through the store, so re-read rather than trusting the copy held here.
        ruleSet = ContactFilterStore.load(this, accountName)
        render()
    }

    override fun onStop() {
        super.onStop()
        if (dirty) {
            // One bump per editing session. The revision invalidates every cached verdict, so a
            // bump per keystroke would re-judge the whole address book repeatedly.
            ruleSet = ruleSet.copy(revision = ruleSet.revision + 1)
            ContactFilterStore.save(this, accountName, ruleSet)
            dirty = false
            SyncLog.i(TAG, "Contact filter rules committed for $accountName (rev ${ruleSet.revision})")
        }
    }

    private fun render() {
        val hasRules = ruleSet.rules.isNotEmpty()
        binding.emptyState.visibility = if (hasRules) View.GONE else View.VISIBLE
        binding.rulesRecycler.visibility = if (hasRules) View.VISIBLE else View.GONE
        // The empty state used to promise that every contact syncs, while the fall-through row two
        // inches below it said not to sync anything. With no rules the fall-through decides
        // everything, so the text has to follow it.
        binding.emptyBody.setText(
            if (ruleSet.defaultAction == RuleAction.EXCLUDE) R.string.contact_filters_empty_none
            else R.string.contact_filters_empty_body
        )
        binding.valueFallthrough.text = getString(actionLabel(ruleSet.defaultAction))
        adapter.notifyDataSetChanged()
    }

    private fun markDirtyAndSave() {
        dirty = true
        // Written immediately so a crash or a swipe away cannot lose the edit; the revision bump
        // that actually triggers re-evaluation still waits for onStop.
        ContactFilterStore.save(this, accountName, ruleSet)
        render()
    }

    private fun move(from: Int, delta: Int) {
        val to = from + delta
        if (to < 0 || to >= ruleSet.rules.size) return
        val rules = ruleSet.rules.toMutableList()
        Collections.swap(rules, from, to)
        ruleSet = ruleSet.copy(rules = rules)
        markDirtyAndSave()
        binding.rulesRecycler.announceForAccessibility(
            getString(if (delta < 0) R.string.contact_filters_move_up else R.string.contact_filters_move_down)
        )
    }

    /**
     * The fall-through, and the only control here that can empty an address book on its own.
     *
     * Switching it to "do not sync" turns every contact no rule claims into a removal, which for
     * a list of one narrow rule is very nearly all of them. So this is the one setting that shows
     * its consequences before it is applied rather than after: the dry run runs first, names the
     * contacts, and the change is only written if the user goes ahead.
     */
    private fun chooseFallthrough() {
        val options = arrayOf(
            getString(R.string.contact_filters_action_sync),
            getString(R.string.contact_filters_action_exclude),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.contact_filters_fallthrough)
            .setItems(options) { _, which ->
                val chosen = if (which == 0) RuleAction.SYNC else RuleAction.EXCLUDE
                if (chosen == ruleSet.defaultAction) return@setItems
                val candidate = ruleSet.copy(defaultAction = chosen)
                if (chosen == RuleAction.SYNC) {
                    // Widening what syncs takes nothing away, so it needs no confirmation.
                    ruleSet = candidate
                    markDirtyAndSave()
                } else {
                    ContactFilterPreviewUi.run(this, syncDatabase, accountName, candidate) {
                        ruleSet = candidate
                        markDirtyAndSave()
                    }
                }
            }
            .show()
    }

    private fun addRule() {
        val rule = FilterRule(
            id = UUID.randomUUID().toString(),
            name = getString(R.string.contact_filters_add),
            action = RuleAction.EXCLUDE,
            direction = RuleDirection.DOWNLOAD,
            match = Combinator.ALL,
            conditions = emptyList(),
        )
        openEditor(rule, isNew = true)
    }

    /**
     * Ready-made rules.
     *
     * These are not a separate kind of thing: a template inserts an ordinary rule which is then
     * edited, reordered or deleted like any other. They exist because nobody would discover
     * unaided that "the contacts Proton invents" is expressible as a subset test over the fields
     * that carry content.
     */
    private fun chooseTemplate() {
        val templates = listOf(
            Template(R.string.contact_filter_tpl_auto_created, R.string.contact_filter_tpl_auto_created_desc) {
                FilterPresets.looksAutoCreated(it)
            },
            Template(R.string.contact_filter_tpl_name_and_email, R.string.contact_filter_tpl_name_and_email_desc) {
                FilterPresets.onlyNameAndEmail(it)
            },
            Template(R.string.contact_filter_tpl_only_email, R.string.contact_filter_tpl_only_email_desc) {
                FilterPresets.onlyEmail(it)
            },
            Template(R.string.contact_filter_tpl_in_any_group, R.string.contact_filter_tpl_in_any_group_desc) {
                FilterPresets.inAnyGroup(it)
            },
        )
        val labels = templates.map { getString(it.titleRes) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.contact_filters_templates)
            .setItems(labels) { _, which ->
                val template = templates[which]
                val rule = template.build(getString(template.titleRes))
                AlertDialog.Builder(this)
                    .setTitle(template.titleRes)
                    .setMessage(template.descRes)
                    .setPositiveButton(R.string.contact_filters_add) { _, _ ->
                        // "Sync only contacts in a group" does nothing over a sync-everything
                        // default, so the template sets the fall-through it needs to mean anything.
                        val flipsFallthrough = rule.action == RuleAction.SYNC &&
                            ruleSet.defaultAction != RuleAction.EXCLUDE
                        var candidate = ruleSet.copy(rules = ruleSet.rules + rule)
                        if (flipsFallthrough) {
                            candidate = candidate.copy(defaultAction = RuleAction.EXCLUDE)
                        }
                        if (flipsFallthrough) {
                            // Same gate the fall-through row enforces. Reaching that setting
                            // through a template is still reaching it, and it is the one control
                            // here that can empty an address book on its own, so the dry run runs
                            // first and names the contacts before anything is written.
                            ContactFilterPreviewUi.run(this, syncDatabase, accountName, candidate) {
                                ruleSet = candidate
                                markDirtyAndSave()
                            }
                        } else {
                            ruleSet = candidate
                            markDirtyAndSave()
                        }
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }
            .show()
    }

    /** A ready-made rule offered in the templates dialog, with the text explaining what it does. */
    private class Template(
        val titleRes: Int,
        val descRes: Int,
        val build: (String) -> FilterRule,
    )

    private fun openEditor(rule: FilterRule, isNew: Boolean) {
        ContactFilterRuleEditorActivity.start(this, accountName, rule.id, isNew, rule)
    }

    private fun actionLabel(action: RuleAction): Int =
        if (action == RuleAction.SYNC) R.string.contact_filters_action_sync
        else R.string.contact_filters_action_exclude

    private fun directionLabel(direction: RuleDirection): Int = when (direction) {
        RuleDirection.DOWNLOAD -> R.string.contact_filters_direction_download
        RuleDirection.UPLOAD -> R.string.contact_filters_direction_upload
        RuleDirection.BOTH -> R.string.contact_filters_direction_both
    }

    private inner class RuleAdapter : RecyclerView.Adapter<RuleAdapter.VH>() {

        var dragStarter: ((RecyclerView.ViewHolder) -> Unit)? = null

        inner class VH(val b: ItemContactFilterRuleBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemContactFilterRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = ruleSet.rules.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val rule = ruleSet.rules[position]
            val b = holder.b

            b.textPosition.text = (position + 1).toString()
            b.textName.text = rule.name
            b.textSummary.text = getString(actionLabel(rule.action)) +
                " · " + getString(directionLabel(rule.direction)) +
                if (rule.conditions.size < 2) "" else " · " + describeConditions(rule)

            // Disabled rules stay in place and keep their number: they are part of the chain the
            // user is reasoning about, just not currently applied.
            val alpha = if (rule.enabled) 1f else 0.45f
            b.textName.alpha = alpha
            b.textSummary.alpha = alpha
            b.textPosition.alpha = alpha

            b.switchEnabled.setOnCheckedChangeListener(null)
            b.switchEnabled.isChecked = rule.enabled
            b.switchEnabled.setOnCheckedChangeListener { _, checked ->
                val rules = ruleSet.rules.toMutableList()
                val idx = holder.bindingAdapterPosition
                if (idx == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                rules[idx] = rules[idx].copy(enabled = checked)
                ruleSet = ruleSet.copy(rules = rules)
                markDirtyAndSave()
            }

            b.btnMoveUp.isEnabled = position > 0
            b.btnMoveUp.alpha = if (position > 0) 1f else 0.3f
            b.btnMoveUp.setOnClickListener {
                val idx = holder.bindingAdapterPosition
                if (idx != RecyclerView.NO_POSITION) move(idx, -1)
            }

            b.btnMoveDown.isEnabled = position < ruleSet.rules.size - 1
            b.btnMoveDown.alpha = if (position < ruleSet.rules.size - 1) 1f else 0.3f
            b.btnMoveDown.setOnClickListener {
                val idx = holder.bindingAdapterPosition
                if (idx != RecyclerView.NO_POSITION) move(idx, 1)
            }

            b.root.setOnClickListener {
                val idx = holder.bindingAdapterPosition
                if (idx != RecyclerView.NO_POSITION) openEditor(ruleSet.rules[idx], isNew = false)
            }
            b.root.setOnLongClickListener {
                dragStarter?.invoke(holder)
                true
            }
        }

        /**
         * How the rule's conditions combine, and nothing else.
         *
         * The count used to be here and is deliberately gone. A counted noun needs a plural form
         * per language, and Polish and Irish do not agree with English about how many of those
         * there are; the number was also the least useful thing on the row, since opening the rule
         * shows the conditions themselves. With one condition there is nothing to combine, so the
         * chip is omitted entirely rather than reading "All conditions" about a single test.
         */
        private fun describeConditions(rule: FilterRule): String = getString(
            if (rule.match == Combinator.ALL) R.string.contact_filter_match_all
            else R.string.contact_filter_match_any
        )
    }

    companion object {
        private const val TAG = "ContactFilterUI"
        const val EXTRA_ACCOUNT_NAME = "account_name"

        fun start(activity: AppCompatActivity, accountName: String) {
            activity.startActivity(
                android.content.Intent(activity, ContactFilterRulesActivity::class.java)
                    .putExtra(EXTRA_ACCOUNT_NAME, accountName)
            )
        }
    }
}
