import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// Release signing material is read from a gitignored keystore.properties at the
// repo root (never committed). Without it (e.g. CI with no secrets), release
// builds are simply unsigned; debug builds are unaffected.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasKeystore = keystorePropertiesFile.exists()
if (hasKeystore) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
}

android {
    namespace = "me.proton.sync"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.proton.sync"
        minSdk = 29
        targetSdk = 36
        versionCode = 200000
        versionName = "2.0.0"
    }

    // Needed only so AGP can find llvm-objcopy for `debugSymbolLevel` below. There is no
    // externalNativeBuild here (libgojni.so arrives prebuilt in libs/gopenpgpbridge.aar), and
    // without this the symbol-extraction task runs and silently emits nothing. Same NDK the
    // bridge is built with, see native/gopenpgp-bridge/build-aar.sh.
    ndkVersion = "27.3.13750724"

    androidResources {
        localeFilters += listOf(
            "en", "de", "fr", "es", "it", "pt", "nl", "pl", "cs", "ro", "hu",
            "sv", "da", "nb", "fi", "el", "bg", "hr", "sk", "sl", "et", "lv", "lt", "ga", "mt"
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Drops resources nothing references. Safe here because nothing is looked up by name:
            // there is no getIdentifier call anywhere in the sources, no res/raw, and the only asset the
            // app adds is THIRD-PARTY-NOTICES.md (bundled below), plain text that names no resource.
            // Those are the three ways a resource stays reachable without R8 being able to see it.
            // It does NOT move Play's DEX optimisation score, which counts code, not resources.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Ship the GopenPGP bridge's native symbol table inside the AAB so Play Console
            // can symbolicate native crashes instead of showing raw addresses. SYMBOL_TABLE
            // (not FULL) keeps function names without the DWARF debug info, which is what
            // stack traces need and costs far less upload size.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }

    // Per-flavor release signing: play uses the Play upload key; direct
    // uses a dedicated key so the two distributions
    // are isolated. Assigned at flavor level — debug variants keep Android's
    // default debug signing (the debug build type's own signingConfig wins).
    signingConfigs {
        if (hasKeystore) {
            create("playRelease") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("playKeyAlias")
                keyPassword = keystoreProperties.getProperty("playKeyPassword")
            }
            create("directRelease") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("directKeyAlias")
                keyPassword = keystoreProperties.getProperty("directKeyPassword")
            }
        }
    }

    // Distribution flavors. Same applicationId (me.proton.sync) — these are the
    // same app delivered through different channels, not separable installs.
    //  - play:   Google Play, gated by Google Play Billing (proprietary).
    //  - direct: Obtainium/GitHub, gated by a Paddle subscription validated
    //            against spp-license.olausson.de. No Google proprietary deps.
    flavorDimensions += "dist"
    productFlavors {
        create("play") {
            dimension = "dist"
            if (hasKeystore) signingConfig = signingConfigs.getByName("playRelease")
        }
        create("direct") {
            dimension = "dist"
            if (hasKeystore) signingConfig = signingConfigs.getByName("directRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "META-INF/**"
        }
    }
}

// The licences screen (LicencesActivity) shows THIRD-PARTY-NOTICES.md from the repository root, the notice the
// published source carries too, and with META-INF stripped above it is the only notice an APK has. It is bundled from
// there at build time rather than kept as a copy under src/, so there is one file to keep current, and it is a
// required input: a build without it fails instead of shipping no notice. Line ends become LF, so the asset is the
// same whether the working tree has LF or Windows' CRLF.
abstract class BundleThirdPartyNotices : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val notices: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val source = notices.get().asFile
        val text = source.readText(Charsets.UTF_8).replace("\r\n", "\n")
        outputDir.get().asFile.resolve(source.name).writeText(text, Charsets.UTF_8)
    }
}

androidComponents {
    onVariants { variant ->
        val bundle = tasks.register<BundleThirdPartyNotices>(
            "bundle${variant.name.replaceFirstChar { it.uppercase() }}ThirdPartyNotices"
        ) {
            notices.set(rootProject.layout.projectDirectory.file("THIRD-PARTY-NOTICES.md"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(bundle, BundleThirdPartyNotices::outputDir)
    }
}

// ical4j reads these three as system properties, live, on every parse. ProtonSyncApp sets them before
// anything parses; the unit tests set them per class, and three test classes parse iCal without setting
// them at all, so how strict the parser was depended on which class ran first. Setting them for the whole
// test JVM removes that ordering. It is also the only lead on a VTimezoneBuilderTest failure that appeared
// once (Antarctica/Troll, "Unparseable date") and never reproduced, so this is a plausible cause removed,
// not a proven fix.
//
// The fourth switches ical4j's OWN time-zone updater off, as the app does for good at start-up
// (IcalTimeZoneUpdates.disable). It has nothing to do with "Update time zones online": that switch chooses the
// app's rule source (TimeZoneRuleSource), fetched by TzurlClient, whose tests run against loopback servers. Never
// set this to "true" to test the switch: that would only bring back ical4j's updater, which sent the phone's
// http.agent to tzurl.org and which 1.11.5 removed. Without it ical4j's own default (on) applied in the tests,
// and every test that parsed a TZID without a VTIMEZONE asked www.tzurl.org for that zone whenever the build
// machine was online. IcalTimeZoneUpdatesTest proves the updater stays off, against a loopback server only.
tasks.withType<Test>().configureEach {
    systemProperty("ical4j.parsing.relaxed", "true")
    systemProperty("ical4j.unfolding.relaxed", "true")
    systemProperty("ical4j.compatibility.outlook", "true")
    systemProperty("net.fortuna.ical4j.timezone.update.enabled", "false")
    // ThirdPartyNoticesTest reads the notice at the repository root: an edit to it alone must re-run the tests.
    inputs.file(rootProject.layout.projectDirectory.file("THIRD-PARTY-NOTICES.md"))
        .withPropertyName("thirdPartyNotices")
        .withPathSensitivity(PathSensitivity.NONE)
    // SrpModulusTest compares the pinned modulus key with the bridge test's copy, which no app class compiles.
    inputs.file(rootProject.layout.projectDirectory.file("native/gopenpgp-bridge/modulus_test.go"))
        .withPropertyName("bridgeModulusTest")
        .withPathSensitivity(PathSensitivity.NONE)
}

val roomVersion = "2.6.1"

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    // SAF tree-URI access for the local data-backup feature: a Worker writes .ics/.vcf/.gpg
    // into a user-chosen folder via a persisted ACTION_OPEN_DOCUMENT_TREE permission, and
    // DocumentFile is needed to create child files/subfolders inside that tree.
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Pin androidx.activity explicitly. The transitively-resolved older version's
    // enableEdgeToEdge() implementation calls the deprecated Window.setStatusBarColor/
    // setNavigationBarColor (flagged by Play on SDK 35). 1.13.0 also re-applies
    // EdgeToEdge on configuration changes. Requires compileSdk 36.
    // VERIFIED 2026-07-26 by disassembling the shipped v1.6.24 dex: 1.13.0 does NOT
    // remove those calls. EdgeToEdgeApi35 (the impl selected when SDK_INT >= 35) still
    // invokes both setters unconditionally with Color.TRANSPARENT. That is upstream
    // behaviour, unavoidable while we use enableEdgeToEdge(), and harmless because both
    // are documented no-ops at SDK 35+. Do not "fix" it by hand-rolling edge-to-edge.
    implementation("androidx.activity:activity-ktx:1.13.0")

    // Material Design. 1.14.0 (with compileSdk 36) guards the Window.setStatusBarColor/
    // setNavigationBarColor calls inside Material's own datepicker behind an
    // SDK_INT < 35 branch; 1.12.0 called them unconditionally and Play flagged it.
    // VERIFIED 2026-07-26 in the shipped v1.6.24 dex (both call sites sit behind
    // `if-ge SDK_INT, 35 -> skip`).
    implementation("com.google.android.material:material:1.14.0")

    // Database encryption at rest. The sync database holds contact names and a complete
    // timeline of calendar start times, which is life metadata for a privacy-focused audience,
    // and Android's own file encryption only protects it while the device is locked.
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Declared explicitly rather than relied on transitively via Material: the contact filter
    // rule list needs ItemTouchHelper for drag-to-reorder, which ships in this artifact, and a
    // Material upgrade that dropped or moved the transitive dependency would break the build in a
    // confusing place.
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Room
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.55")
    ksp("com.google.dagger:hilt-compiler:2.55")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Networking (OkHttp + Retrofit)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // BCrypt for Proton SRP password hashing
    implementation("at.favre.lib:bcrypt:0.10.2")

    // BouncyCastle PROVIDER ONLY (bcprov). All OpenPGP work moved to GopenPGP; bcpg and bcutil
    // are gone with it.
    //
    // Do NOT remove bcprov: BouncyCastleSetup installs it as the JCA provider "BC" because
    // Android 16 (targetSdk 36) drops the platform provider and with it the default KeyStore
    // type "BKS". Without the bundled provider, every OkHttp TLS trust-manager init throws
    // "KeyStoreException: BKS not found" — observed for real on the first Paddle licence check
    // at app start during v1.6.22 bring-up. That is the TLS stack, not OpenPGP.
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")

    // GopenPGP via a gomobile binding we build ourselves — see native/gopenpgp-bridge/README.md.
    // BouncyCastle cannot parse Proton's post-quantum (v6) keys: it has no RFC 9980 algorithm IDs
    // (30-36) in its OpenPGP layer, not in 1.80 and not on its main branch as of August 2026.
    // Vendored as a prebuilt AAR so an ordinary build needs no Go toolchain. Rebuild it with
    // build-aar.sh and record the new SHA-256 in app/libs/gopenpgpbridge.aar.sha256;
    // native/gopenpgp-bridge/README.md says what a rebuild does and does not reproduce.
    //
    // NOT me.proton.crypto:android-golib. That prebuilt is GPLv3, and linking it would put this app under GPLv3.
    // GopenPGP itself is MIT, which is exactly why we build our own binding.
    implementation(files("libs/gopenpgpbridge.aar"))

    // vCard parsing
    implementation("com.googlecode.ez-vcard:ez-vcard:0.12.1")

    // iCalendar parsing
    implementation("org.mnode.ical4j:ical4j:3.2.14")
    implementation("org.slf4j:slf4j-nop:2.0.9") // silence ical4j's SLF4J warnings

    // Google Play Billing (subscriptions) — play flavor only; must not leak into the
    // direct (Obtainium/Paddle) build, which carries no Google proprietary code.
    // Play mandates 8.0.0+ from 2026-08-31; 8.3.0 is the newest that works with
    // Kotlin 2.1.x (billing-ktx 9.x ships Kotlin 2.3 metadata and needs Kotlin 2.2+,
    // which would cascade into a KSP/Hilt/Room toolchain bump — do that separately).
    "playImplementation"("com.android.billingclient:billing-ktx:8.3.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in plain JVM tests; the real one lets the backup model be tested.
    testImplementation("org.json:json:20240303")
}
