package me.proton.sync.backup

import ezvcard.VCard
import ezvcard.property.RawProperty

/**
 * Makes a parsed vCard writable again.
 *
 * ez-vcard validates on WRITE what it does not validate on READ: `VObjectWriter.validate` throws
 * IllegalArgumentException for a property name holding a character it dislikes or starting with
 * whitespace, for a group name the same, and for a parameter with a null name. Its reader checks
 * none of that. So a card Proton stores, that parses here without a murmur, can still throw on the
 * way out, and that throw used to abort the entire backup run before a single byte reached disk,
 * taking the calendars and every other account down with it.
 *
 * Lives apart from [ContactsBackupBuilder] so the rule can be tested against the real library
 * rather than reasoned about.
 */
object VCardRepair {

    /**
     * Repairs exactly what the writer refuses, and nothing else.
     *
     * Only a property's NAME and GROUP can be illegal in a card that already parsed; the values are
     * the user's data and are never touched. A name is rebuilt from the characters vinnie permits,
     * with anything else becoming a hyphen, and a property left with no name at all is the one case
     * where the property itself has to go. An illegal group is simply dropped, since a group is a
     * display grouping and losing it costs nothing next to losing the contact.
     *
     * Real cards that land here: `x.y.z:value`, which parses as group `x` and name `y.z`; a doubled
     * group like `item1.item1.X-ABLabel`; and `item1.:value`, which parses to an empty name.
     *
     * @return how many repairs were made, for the log.
     */
    fun repair(card: VCard): Int {
        var repairs = 0
        for (property in card.properties.toList()) {
            val group = property.group
            if (group != null && !group.all(::writable)) {
                property.group = null
                repairs++
            }
            if (property !is RawProperty) continue
            val name = property.propertyName
            val clean = name?.map { if (writable(it)) it else '-' }?.joinToString("")
            when {
                clean.isNullOrBlank() -> { card.removeProperty(property); repairs++ }
                clean != name -> { property.propertyName = clean; repairs++ }
            }
        }
        return repairs
    }

    /** The character set vinnie's writer accepts in a property or group name. */
    private fun writable(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-'
}
