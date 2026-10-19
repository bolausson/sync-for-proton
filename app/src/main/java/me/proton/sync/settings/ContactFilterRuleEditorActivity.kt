package me.proton.sync.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import me.proton.sync.R
import me.proton.sync.contacts.filter.Combinator
import me.proton.sync.contacts.filter.Condition
import me.proton.sync.contacts.filter.ContactFilterStore
import me.proton.sync.contacts.filter.FieldKind
import me.proton.sync.contacts.filter.FilterRule
import me.proton.sync.contacts.filter.RuleAction
import me.proton.sync.contacts.filter.RuleDirection
import me.proton.sync.contacts.filter.RuleField
import me.proton.sync.contacts.filter.RuleOp
import me.proton.sync.contacts.filter.VCardContactView
import me.proton.sync.databinding.ActivityContactFilterRuleEditorBinding
import javax.inject.Inject

/**
 * Edits one rule.
 *
 * Laid out to read as a sentence down the screen: this rule *does* something, *applies* in a
 * direction, *when* some conditions hold. The labels are the connecting words, which is why they
 * are quiet rather than headings.
 */
@AndroidEntryPoint
class ContactFilterRuleEditorActivity : AppCompatActivity() {

    @Inject lateinit var syncDatabase: me.proton.sync.db.SyncDatabase

    private lateinit var binding: ActivityContactFilterRuleEditorBinding
    private lateinit var accountName: String
    private var isNew = false
    private lateinit var rule: FilterRule

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityContactFilterRuleEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        accountName = intent.getStringExtra(EXTRA_ACCOUNT_NAME).orEmpty()
        isNew = intent.getBooleanExtra(EXTRA_IS_NEW, false)
        val json = intent.getStringExtra(EXTRA_RULE_JSON)
        if (accountName.isEmpty() || json == null) {
            finish()
            return
        }
        // Prefer the instance state: conditions live only in this field and are drawn into
        // programmatically created views with no ids, so a rotation used to redraw from the intent
        // and silently drop every condition added in this session. The name survived, because that
        // view has an id, which made the form look intact right up until Save wrote the old
        // condition list back over the user's work.
        val restored = savedInstanceState?.getString(STATE_RULE_JSON)
        rule = Gson().fromJson(restored ?: json, FilterRule::class.java)

        binding.toolbar.title = getString(
            if (isNew) R.string.contact_filter_edit_title_new else R.string.contact_filter_edit_title
        )
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.inputName.setText(rule.name)

        binding.groupAction.check(
            if (rule.action == RuleAction.SYNC) R.id.btn_action_sync else R.id.btn_action_exclude
        )
        binding.groupDirection.check(
            when (rule.direction) {
                RuleDirection.DOWNLOAD -> R.id.btn_dir_download
                RuleDirection.UPLOAD -> R.id.btn_dir_upload
                RuleDirection.BOTH -> R.id.btn_dir_both
            }
        )
        binding.groupMatch.check(
            if (rule.match == Combinator.ALL) R.id.btn_match_all else R.id.btn_match_any
        )

        binding.btnAddCondition.setOnClickListener { editCondition(null) }
        binding.btnSave.setOnClickListener { save() }
        binding.btnPreview.setOnClickListener { preview() }
        binding.btnDeleteRule.setOnClickListener { confirmDelete() }
        binding.btnDeleteRule.visibility = if (isNew) android.view.View.GONE else android.view.View.VISIBLE

        renderConditions()
    }

    private fun renderConditions() {
        val container: LinearLayout = binding.conditionsContainer
        container.removeAllViews()
        rule.conditions.forEachIndexed { index, condition ->
            val row = TextView(this).apply {
                text = describe(condition)
                textSize = 14f
                setTextColor(getColor(R.color.sfp_text))
                setPadding(0, 20, 0, 20)
                isClickable = true
                setOnClickListener { editCondition(index) }
            }
            container.addView(row)
        }
    }

    /**
     * A condition in words.
     *
     * Rendered as field, test, value with no glue words: those would need a positional placeholder
     * per operator, which is the hardest kind of string to translate and the easiest to get subtly
     * wrong in twenty-four languages.
     */
    private fun describe(condition: Condition): String {
        val parts = mutableListOf(label(condition.field), label(condition.op, condition.field))
        if (condition.field.kind == FieldKind.FIELD_SET) {
            // Field-set operands are field names, not values, so they render as the labels the
            // picker shows rather than as raw constants like FORMATTED_NAME.
            val chosen = condition.values.mapNotNull { RuleField.fromName(it) }.map { label(it) }
            if (chosen.isNotEmpty()) parts += chosen.joinToString(", ")
        } else if (condition.values.isNotEmpty()) {
            parts += condition.values.joinToString(", ")
        }
        condition.number?.let { parts += it.toString() }
        val text = parts.joinToString(SEPARATOR)
        return if (condition.negate) getString(R.string.contact_filter_condition_not, text) else text
    }

    /**
     * The condition dialog.
     *
     * Three controls share one slot, because a condition takes one of three quite different
     * operands, and offering the wrong box for the field is how a rule ends up meaning nothing:
     * free text for ordinary fields, a number for the counting tests, and a checklist of field
     * names for "only has these fields", whose operands are not values at all.
     *
     * Values are one per line rather than comma separated. A comma is an ordinary character in an
     * organisation name and a structural one in a regular expression, so splitting on it turned a
     * pattern like the one for a phone prefix into two fragments that both failed to compile and
     * silently matched nothing.
     */
    private fun editCondition(index: Int?) {
        val existing = index?.let { rule.conditions[it] }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        // FORMATTED_NAME and DISPLAY_NAME are the same value: what Proton calls the display name,
        // read from the encrypted card and from the contact-list header respectively. Offering both
        // would put two identically named choices in one list, so only the header one is offered,
        // and it is the cheaper of the two to evaluate besides.
        val offered = RuleField.entries.filter { it != RuleField.FORMATTED_NAME }
        // A field kept out of the list still has to appear when the rule being edited uses it.
        // Without this the lookup below falls back to the first entry and quietly rewrites the
        // user's condition to something else the moment they press Save.
        val fields = existing?.field
            ?.takeIf { it !in offered }
            ?.let { offered + it }
            ?: offered
        val fieldSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ContactFilterRuleEditorActivity,
                android.R.layout.simple_spinner_dropdown_item,
                fields.map { label(it) }
            )
            setSelection(fields.indexOf(existing?.field ?: RuleField.EMAIL).coerceAtLeast(0))
        }
        val opSpinner = Spinner(this)

        val valueInput = EditText(this).apply {
            hint = getString(R.string.contact_filter_condition_values_hint)
            setText(existing?.values.orEmpty().joinToString("\n"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            maxLines = 4
        }
        val numberInput = EditText(this).apply {
            hint = getString(R.string.contact_filter_condition_number)
            // Seeded from number, not from values. Reading the text box for a numeric condition is
            // what used to blank the operand whenever somebody opened the dialog and pressed Save,
            // turning "in at least one group" into a rule matching nobody, which over an exclude
            // fall-through proposes removing the entire address book.
            setText(existing?.number?.toString().orEmpty())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val chosenFields = linkedSetOf<RuleField>().apply {
            addAll(existing?.values.orEmpty().mapNotNull { RuleField.fromName(it) })
        }
        val fieldSetButton = Button(this)
        fieldSetButton.text = fieldSetLabel(chosenFields)
        fieldSetButton.setOnClickListener {
            pickFields(chosenFields) { fieldSetButton.text = fieldSetLabel(chosenFields) }
        }

        val negateBox = CheckBox(this).apply {
            text = getString(R.string.contact_filter_condition_negate)
            isChecked = existing?.negate == true
        }

        val valueLabel = sectionLabel(getString(R.string.contact_filter_condition_value))
        val numberLabel = sectionLabel(getString(R.string.contact_filter_condition_number))
        val fieldsLabel = sectionLabel(getString(R.string.contact_filter_condition_fields))

        fun currentField() = fields[fieldSpinner.selectedItemPosition]

        @Suppress("UNCHECKED_CAST")
        fun currentOps(): List<RuleOp> = (opSpinner.tag as? List<RuleOp>).orEmpty()

        fun currentOp(): RuleOp? = currentOps().getOrNull(opSpinner.selectedItemPosition)

        fun showRelevantControls() {
            val field = currentField()
            val op = currentOp()
            // EXISTS ignores its operand whatever the field, so asking for one would be asking
            // the user to type a number that changes nothing.
            val wantsNumber = op != RuleOp.EXISTS &&
                (field.kind == FieldKind.NUMBER || op?.name?.startsWith("COUNT") == true)
            val wantsFieldSet = field.kind == FieldKind.FIELD_SET && op != RuleOp.EXISTS
            val wantsText = !wantsNumber && !wantsFieldSet && op != RuleOp.EXISTS
            for ((view, visible) in listOf(
                valueLabel to wantsText, valueInput to wantsText,
                numberLabel to wantsNumber, numberInput to wantsNumber,
                fieldsLabel to wantsFieldSet, fieldSetButton to wantsFieldSet,
            )) {
                view.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }

        fun refreshOps() {
            val field = currentField()
            val ops = RuleOp.entries.filter { it.isValidFor(field) }
            opSpinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, ops.map { label(it, field) }
            )
            opSpinner.tag = ops
            val want = existing?.op
            if (want != null && ops.contains(want)) opSpinner.setSelection(ops.indexOf(want))
            showRelevantControls()
        }
        fieldSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshOps()
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        opSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = showRelevantControls()
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        refreshOps()

        root.addView(sectionLabel(getString(R.string.contact_filter_condition_field)))
        root.addView(fieldSpinner)
        root.addView(sectionLabel(getString(R.string.contact_filter_condition_op)))
        root.addView(opSpinner)
        root.addView(valueLabel)
        root.addView(valueInput)
        root.addView(numberLabel)
        root.addView(numberInput)
        root.addView(fieldsLabel)
        root.addView(fieldSetButton)
        root.addView(negateBox)
        showRelevantControls()

        val builder = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton(R.string.contact_filter_save, null)
            .setNegativeButton(R.string.btn_cancel, null)
        if (index != null) {
            builder.setNeutralButton(R.string.contact_filter_condition_remove) { _, _ ->
                val list = rule.conditions.toMutableList()
                list.removeAt(index)
                rule = rule.copy(conditions = list)
                renderConditions()
            }
        }
        val dialog = builder.create()
        // Save is wired after show() so an incomplete condition can keep the dialog open rather
        // than being written in a shape that quietly matches nothing.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val field = currentField()
                val op = currentOp() ?: return@setOnClickListener
                val wantsNumber = op != RuleOp.EXISTS &&
                    (field.kind == FieldKind.NUMBER || op.name.startsWith("COUNT"))
                val wantsFieldSet = field.kind == FieldKind.FIELD_SET && op != RuleOp.EXISTS
                val values = when {
                    wantsFieldSet -> chosenFields.map { it.name }
                    wantsNumber -> emptyList()
                    else -> valueInput.text.toString().lines()
                        .map { it.trim() }.filter { it.isNotEmpty() }
                }
                val number = if (wantsNumber) numberInput.text.toString().trim().toLongOrNull() else null

                val complaint = when {
                    wantsNumber && number == null -> R.string.contact_filter_condition_needs_number
                    wantsFieldSet && values.isEmpty() -> R.string.contact_filter_condition_needs_fields
                    !wantsNumber && !wantsFieldSet && op != RuleOp.EXISTS && values.isEmpty() ->
                        R.string.contact_filter_condition_needs_value
                    else -> null
                }
                if (complaint != null) {
                    Toast.makeText(this, complaint, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val condition = Condition(
                    field = field,
                    op = op,
                    negate = negateBox.isChecked,
                    values = values,
                    // Carried over, never rebuilt. A group condition matches on Proton label id and
                    // falls back to the name, so dropping the ids here made the rule stop working
                    // the first time that group was renamed on the web.
                    refIds = if (field == existing?.field) existing.refIds else emptyList(),
                    number = number,
                )
                val list = rule.conditions.toMutableList()
                if (index == null) list.add(condition) else list[index] = condition
                rule = rule.copy(conditions = list)
                renderConditions()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** Multi-select over the fields a contact can actually carry content in. */
    private fun pickFields(selection: MutableSet<RuleField>, onDone: () -> Unit) {
        val choices = VCardContactView.PICKER_FIELDS
        val labels = choices.map { label(it) }.toTypedArray()
        val checked = choices.map { it in selection }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.contact_filter_condition_fields)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) selection.add(choices[which]) else selection.remove(choices[which])
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun fieldSetLabel(selection: Set<RuleField>): String =
        if (selection.isEmpty()) getString(R.string.contact_filter_condition_choose_fields)
        else selection.joinToString(", ") { label(it) }

    /** Interpunct with hair spaces, matching the summary chips on the rule list. */
    private val SEPARATOR = "  \u00b7  "

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(getColor(R.color.sfp_text_faint))
        setPadding(0, 24, 0, 4)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // currentRule() rather than the backing field, so the toggles and the name are carried
        // across with the conditions instead of only the half the view hierarchy restores.
        outState.putString(STATE_RULE_JSON, Gson().toJson(currentRule()))
    }

    /** The rule as the form currently describes it, whether or not it is valid yet. */
    private fun currentRule(): FilterRule = rule.copy(
        name = binding.inputName.text?.toString()?.trim().orEmpty(),
        action = if (binding.groupAction.checkedButtonId == R.id.btn_action_sync)
            RuleAction.SYNC else RuleAction.EXCLUDE,
        direction = when (binding.groupDirection.checkedButtonId) {
            R.id.btn_dir_upload -> RuleDirection.UPLOAD
            R.id.btn_dir_both -> RuleDirection.BOTH
            else -> RuleDirection.DOWNLOAD
        },
        match = if (binding.groupMatch.checkedButtonId == R.id.btn_match_all)
            Combinator.ALL else Combinator.ANY,
    )

    /**
     * The dry run.
     *
     * Evaluates the rule set this screen *would* save against the account's contacts, read and
     * decrypted from Proton (falling back to the copies on the phone if that fails), and writes
     * nothing. This is the one guard in the app that protects the user from their own input
     * rather than from a server glitch, so it runs before saving, not after: by the time a
     * removal notification appears, the rule is already live.
     */
    private fun preview() {
        val draft = currentRule()
        if (draft.conditions.isEmpty()) {
            Toast.makeText(this, R.string.contact_filter_needs_condition, Toast.LENGTH_LONG).show()
            return
        }

        val stored = ContactFilterStore.load(this, accountName)
        val rules = stored.rules.toMutableList()
        // Spliced into its own position rather than tested alone. First match wins, so a rule
        // above this one may be what actually decides a contact, and testing this rule in
        // isolation would report removals that are never going to happen.
        val index = rules.indexOfFirst { it.id == draft.id }
        // Forced on: the user asked what this rule does, and answering "nothing" because the rule
        // is currently switched off in the list would read as the editor being broken.
        val candidate = draft.copy(enabled = true)
        if (index >= 0) rules[index] = candidate else rules.add(candidate)

        ContactFilterPreviewUi.run(this, syncDatabase, accountName, stored.copy(rules = rules))
    }

    private fun save() {
        val draft = currentRule()
        if (draft.name.isEmpty()) {
            Toast.makeText(this, R.string.contact_filter_needs_name, Toast.LENGTH_LONG).show()
            return
        }
        if (draft.conditions.isEmpty()) {
            // The evaluator treats a conditionless rule as never matching, which is the safe
            // reading, but saving one silently is just a rule that does nothing.
            Toast.makeText(this, R.string.contact_filter_needs_condition, Toast.LENGTH_LONG).show()
            return
        }
        rule = draft

        val set = ContactFilterStore.load(this, accountName)
        val rules = set.rules.toMutableList()
        val idx = rules.indexOfFirst { it.id == rule.id }
        if (idx >= 0) rules[idx] = rule else rules.add(rule)
        // Revision bumped here rather than in the list screen's onStop: an edit made through this
        // screen is a deliberate change and has to invalidate the cached verdicts.
        ContactFilterStore.save(this, accountName, set.copy(rules = rules, revision = set.revision + 1))
        Toast.makeText(this, R.string.contact_filters_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setMessage(R.string.contact_filter_delete_confirm)
            .setPositiveButton(R.string.contact_filters_delete) { _, _ ->
                val set = ContactFilterStore.load(this, accountName)
                ContactFilterStore.save(
                    this, accountName,
                    set.copy(rules = set.rules.filterNot { it.id == rule.id }, revision = set.revision + 1)
                )
                finish()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun label(field: RuleField): String = FilterLabels.of(this, field)

    private fun label(op: RuleOp, field: RuleField? = null): String =
        FilterLabels.of(this, op, field)

    companion object {
        private const val STATE_RULE_JSON = "state_rule_json"
        private const val EXTRA_ACCOUNT_NAME = "account_name"
        private const val EXTRA_RULE_JSON = "rule_json"
        private const val EXTRA_IS_NEW = "is_new"

        fun start(
            context: Context,
            accountName: String,
            @Suppress("UNUSED_PARAMETER") ruleId: String,
            isNew: Boolean,
            rule: FilterRule,
        ) {
            context.startActivity(
                Intent(context, ContactFilterRuleEditorActivity::class.java)
                    .putExtra(EXTRA_ACCOUNT_NAME, accountName)
                    .putExtra(EXTRA_IS_NEW, isNew)
                    .putExtra(EXTRA_RULE_JSON, Gson().toJson(rule))
            )
        }
    }
}
