package me.proton.sync

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import java.util.Locale

/**
 * Plural texts, picked by the plural rules of the language the text is in.
 *
 * Android picks a plural's form by the phone's language, also when the text comes from another one. On a phone in a
 * language the app does not ship (Russian, Ukrainian, Serbian, Icelandic...) the English text loads, and the phone's
 * "one", which there also covers 21 and 31, made it "21 contact". Each strings.xml names its own language
 * (plural_rules_language), and when that is not the phone's, the form is picked by the text's language instead. Every
 * plural in the app goes through here (PluralFormsTest checks).
 */
object Plurals {

    fun text(context: Context, @PluralsRes id: Int, count: Int, vararg args: Any?): String =
        resourcesFor(context).getQuantityString(id, count, *args)

    private fun resourcesFor(context: Context): Resources {
        val res = context.resources
        val textLanguage = res.getString(R.string.plural_rules_language)
        if (res.configuration.locales[0].language == textLanguage) return res
        val conf = Configuration(res.configuration).apply { setLocale(Locale.forLanguageTag(textLanguage)) }
        return context.createConfigurationContext(conf).resources
    }
}
