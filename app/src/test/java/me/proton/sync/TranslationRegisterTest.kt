package me.proton.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Dutch, Spanish and Romanian address the user formally on every screen: u/uw, usted, and dumneavoastră with the -ți
 * verbs, as Proton's own apps do in Dutch, Romanian and Latin American Spanish, and as most of each file already did.
 * Up to 2.0.3 a third of each main file and all of the direct subscription strings said je, tú or tu, so one screen
 * could change register between two lines. German (Sie) and Estonian (teie) had strays too. Only markers that can be
 * nothing but the informal "you" are listed: a Spanish "usa" or a Romanian "verifică" is also the third person ("the
 * app uses", "the app checks"), so those count only where an instruction starts. The files are read as in
 * [ReleaseStringsTest]; app/build.gradle.kts declares both sets as test inputs, so an edited translation alone re-runs
 * this test.
 */
class TranslationRegisterTest {

    private companion object {
        // A word edge for any script: Kotlin's \w is ASCII on the JVM, and ț, ș, é are letters here.
        const val BEFORE = "(?<![\\p{L}\\p{N}_-])"
        const val AFTER = "(?![\\p{L}\\p{N}_])"

        fun words(vararg w: String) = Regex(BEFORE + "(?:" + w.joinToString("|") + ")" + AFTER, RegexOption.IGNORE_CASE)

        /** Pronouns, possessives and verb forms that no other person shares. */
        val INFORMAL = mapOf(
            "nl" to words("je", "jij", "jou", "jouw", "jouwe", "jullie", "jezelf"),
            "es" to words(
                "tú", "tu", "tus", "te", "ti", "contigo",
                "puedes", "quieres", "quieras", "tienes", "debes", "has", "sigues", "acabas", "usaste", "añadiste",
                "introdujiste", "eliges", "elijas", "intercambias", "ves", "omites", "canceles", "cambias", "deseas",
                "prefieres", "necesitas", "estás", "eres",
                "inténtalo", "cópialo", "pégalo", "cámbialos", "cámbialo", "muévelo", "compártelo", "déjalo",
                "actívalo", "desactívalo", "ponle", "suscríbete", "asegúrate", "mantén",
            ),
            // te-ai and le-ai are the informal perfect; the formal one is v-ați, le-ați.
            "ro" to Regex(
                words(
                    "tu", "te", "tine", "ție", "tău", "ta", "tale", "tăi", "îți", "ți", "să-ți", "poți", "vrei", "ești", "fii",
                    "anulezi", "preferi", "suspectezi", "raportezi", "deții", "bazezi", "adaugi", "modifici", "ștergi",
                    "trimiți", "găsești", "treci", "suprascrii", "încerci", "aștepți", "introdu", "dezactiveaz-o",
                    "atinge-o", "folosește-l", "lipește-l", "copiază-l", "trimite-le", "abonează-te", "păstrează-le",
                    "asigură-te", "nu sincroniza",
                ).pattern + "|\\p{L}+-ai" + AFTER,
                RegexOption.IGNORE_CASE,
            ),
            "de" to words("du", "dich", "dir", "dein", "deine", "deinen", "deinem", "deiner", "deines"),
            "et" to words("sina", "sinu", "sul", "sind", "sulle", "sinuga", "sa", "su"),
        )

        /**
         * Informal imperatives that are also the third person, counted only where an instruction starts: at the start,
         * after a sentence end or a colon, or after "por favor" or "apoi". The Spanish "copia" (a backup) and "activa"
         * (active) are left out: they open neutral labels.
         */
        val OPENERS = mapOf(
            "es" to "introduce elige añade pega comprueba instala renueva actualiza espera usa restaura quita revisa " +
                "toca pulsa selecciona guarda abre desactiva cambia pon",
            "ro" to "încearcă verifică apasă folosește lipește copiază introdu instalează activează alege actualizează " +
                "reînnoiește lasă atinge păstrează",
        ).mapValues { (_, list) ->
            Regex(
                "(?:^|[.:;!?¿¡]\\s+|por favor\\s+|apoi\\s+)(?:" + list.split(" ").joinToString("|") + ")" + AFTER,
                setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
            )
        }

        /** Second-person imperatives that open a Romanian button or title: a capitalised text with no sentence end. */
        val RO_LABEL_VERBS = ("sincronizează trimite salvează actualizează schimbă începe reînnoiește gestionează alege " +
            "setează omite șterge păstrează păstrează-le importă exportă golește copiază partajează folosește mută " +
            "testează elimină afișează introdu activează dezactivează anulează lipește verifică încearcă include " +
            "deschide adaugă caută restaurează decriptează criptează confirmă deblochează configurează continuă " +
            "editează selectează").split(" ").toSet()

        /** The user speaking to the app ("Don't remind me again"), in the user's own voice. */
        val USER_VOICE = setOf("backup_reminder_dont_remind", "backup_reminder_optout_confirm", "backup_reminder_optout_cancel")

        val ESCAPE = Regex("\\\\u([0-9a-fA-F]{4})")
        val ENTRY = Regex("<(string|plurals|string-array) name=\"([^\"]+)\"([^>]*)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
        val ITEM = Regex("<item[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val SENTENCE_END = Regex("[.?!:]\\s*\$")

        /** Romanian ș and ț with a cedilla (ş, ţ), written as escapes so this source shows which is meant. */
        const val CEDILLA = "\u015f\u0163\u015e\u0162"
    }

    /** Every translatable text in one strings.xml as (key, text), one per plural or array item, \u escapes decoded. */
    private fun texts(xml: File): List<Pair<String, String>> =
        ENTRY.findAll(COMMENT.replace(xml.readText(), ""))
            .filterNot { it.groupValues[3].contains("translatable=\"false\"") }
            .flatMap { m ->
                val body = ESCAPE.replace(m.groupValues[4]) { it.groupValues[1].toInt(16).toChar().toString() }
                val parts = if (m.groupValues[1] == "string") listOf(body) else ITEM.findAll(body).map { it.groupValues[1] }.toList()
                parts.map { m.groupValues[2] to it.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n") }
            }.toList()

    /** The informal markers in [text]; empty when it addresses the user formally or not at all. */
    private fun informal(lang: String, text: String): List<String> {
        val t = text.trim()
        val found = INFORMAL.getValue(lang).findAll(t).map { it.value }.toMutableList()
        OPENERS[lang]?.findAll(t)?.forEach { found += it.value.trim() }
        if (lang == "ro") {
            val first = t.substringBefore(' ')
            val label = !SENTENCE_END.containsMatchIn(t) && t.split(Regex("\\s+")).size <= 8
            if (label && first.firstOrNull()?.isUpperCase() == true && first.lowercase() in RO_LABEL_VERBS) found += "label $first"
        }
        return found
    }

    private fun file(sourceSet: String, lang: String) = File("src/$sourceSet/res/values-$lang/strings.xml")

    @Test
    fun `Dutch, Spanish, Romanian, German and Estonian address the user formally in both sets`() {
        val offenders = mutableListOf<String>()
        for (lang in listOf("nl", "es", "ro", "de", "et")) for (set in listOf("main", "direct")) {
            val entries = texts(file(set, lang))
            assertTrue("$set/values-$lang has only ${entries.size} texts", entries.size >= (if (set == "main") 600 else 25))
            for ((key, text) in entries) {
                if (key in USER_VOICE) continue
                val found = informal(lang, text)
                if (found.isNotEmpty()) offenders += "$set/values-$lang $key: $found"
            }
        }
        assertTrue(offenders.joinToString("\n", "${offenders.size} informal texts:\n"), offenders.isEmpty())
    }

    @Test
    fun `the markers find the informal you and pass formal and third-person texts`() {
        // Made-up texts, one per pattern the test above relies on, so a broken pattern cannot pass it vacuously.
        val informalTexts = mapOf(
            "nl" to listOf("Plak je ID hier.", "Als je jouw versie behoudt, vervang je die van hem."),
            "es" to listOf("Inténtalo de nuevo.", "Pago fallido: actualiza el método de pago.", "Comprobando tus contactos…"),
            "ro" to listOf("Încearcă din nou.", "Le-ai pierdut pe amândouă?", "Salvează", "Nu sincroniza"),
            "de" to listOf("Deine Kontakte werden gelesen…", "Prüfe, ob du online bist."),
            "et" to listOf("Sinu kontaktid", "Kas sa oled kindel?"),
        )
        val formalTexts = mapOf(
            "nl" to listOf("Plak uw ID hier.", "Controleer de ID en probeer het opnieuw."),
            "es" to listOf("Inténtelo de nuevo.", "La aplicación usa los datos del teléfono.", "Copia de seguridad exportada", "Activa, se renueva el %1\$s"),
            "ro" to listOf("Încercați din nou.", "Le-ați pierdut pe amândouă?", "Salvați", "Salvare", "Nu acum", "începe cu",
                "Sincronizează acum contactele și calendarul pentru acest cont."),
            "de" to listOf("Ihre Kontakte werden gelesen…", "Prüfen Sie, ob Sie online sind."),
            "et" to listOf("Teie kontaktid", "Kas olete kindel?"),
        )
        for ((lang, list) in informalTexts) for (t in list) assertTrue("$lang missed: $t", informal(lang, t).isNotEmpty())
        for ((lang, list) in formalTexts) for (t in list) assertEquals("$lang flagged: $t", emptyList<String>(), informal(lang, t))
    }

    @Test
    fun `a text written with unicode escapes is read decoded`() {
        // values-ro writes about a hundred lines as \u escapes; undecoded, "Alegeți" would hide every -ți check.
        val xml = File.createTempFile("register", ".xml").apply { deleteOnExit() }
        xml.writeText("<resources><string name=\"probe\">\\u00cencearc\\u0103 din nou.</string></resources>")
        val (key, text) = texts(xml).single()
        assertEquals("probe", key)
        assertEquals("Încearcă din nou.", text)
        assertTrue(informal("ro", text).isNotEmpty())
    }

    /**
     * The direct subscription dialog names the Settings button the user should tap, so the two must read the same in
     * every language; the Romanian button is one of the labels the 2.0.4 register change rewords.
     */
    @Test
    fun `every subscription dialog names the report button as the button reads`() {
        var checked = 0
        for (dir in File("src/main/res").listFiles()!!.filter { it.name == "values" || Regex("values-[a-z]{2}").matches(it.name) }.sortedBy { it.name }) {
            val main = File(dir, "strings.xml")
            val direct = File("src/direct/res/${dir.name}/strings.xml")
            if (!main.isFile || !direct.isFile) continue
            val button = texts(main).single { it.first == "btn_send_report" }.second
            val message = texts(direct).single { it.first == "spp_subscribe_dialog_message" }.second
            assertTrue("${dir.name}: the dialog does not quote \"$button\": $message", message.contains(button))
            checked++
        }
        assertEquals(25, checked)
    }

    /** Romanian writes ș and ț with a comma below; a cedilla ţ looks the same and would slip past every -ți check. */
    @Test
    fun `Romanian writes s and t with the comma below`() {
        for (set in listOf("main", "direct")) {
            val bad = texts(file(set, "ro")).filter { (_, t) -> t.any { it in CEDILLA } }.map { it.first }
            assertEquals("$set/values-ro", emptyList<String>(), bad)
        }
    }
}
