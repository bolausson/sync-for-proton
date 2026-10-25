# Sync for Proton

**Sync for Proton** syncs your Proton Contacts and Proton Calendar into Android's
native contacts and calendar, so they work everywhere on your phone: the dialer,
lock screen, smartwatch, and any calendar or contacts app. No Google account required.

This repository hosts **signed APK releases** for installing outside the Google Play
Store (for example on GrapheneOS or any de-Googled Android), and the **source code of
each release**. Development happens in a private repository; every release adds one
commit here with exactly the source that release was built from.

[![Latest release](https://img.shields.io/github/v/release/bolausson/sync-for-proton?label=latest&sort=semver)](https://github.com/bolausson/sync-for-proton/releases/latest)

**[⬇ Download the latest APK](https://github.com/bolausson/sync-for-proton/releases/latest/download/Sync-for-Proton.apk)**: a permanent link that always points to the newest release.

## Requirements

- Android 10 (API 29) or newer
- A Proton account
- A subscription: **7-day free trial**, then €2.39 / month or €23.99 / year (see *Subscribing* below)

## Install with Obtainium (recommended)

[Obtainium](https://github.com/ImranR98/Obtainium) installs and auto-updates apps
straight from GitHub releases. With Obtainium already installed, tap the button to add
this app in one step:

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22me.proton.sync%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fbolausson%2Fsync-for-proton%22%2C%22author%22%3A%22bolausson%22%2C%22name%22%3A%22Sync%20for%20Proton%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%7D%22%2C%22overrideSource%22%3Anull%7D"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Add to Obtainium" height="54"></a>

Or add it manually:

1. Install Obtainium.
2. Tap **Add App** and paste this repo URL:
   `https://github.com/bolausson/sync-for-proton`
3. Obtainium finds the latest release APK. Tap **Install**.
4. Future updates show up in Obtainium automatically.

## Install from Uptodown

Also available on [Uptodown](https://sync-provider-for-proton.en.uptodown.com/android), which can keep the app updated via the Uptodown app:

<a href="https://sync-provider-for-proton.en.uptodown.com/android" title="Download Sync Provider for Proton on Uptodown">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://stc.utdstc.com/img/mediakit/download-aao-small-w.png">
    <img src="https://stc.utdstc.com/img/mediakit/download-aao-small.png" alt="Download on Uptodown" height="54">
  </picture>
</a>

## Install manually

1. Download the latest `.apk` from the [Releases](https://github.com/bolausson/sync-for-proton/releases) page.
2. Open it on your device and allow installing from this source if prompted.

## Subscribing / activating

This build is paid via the web (no Google Play Billing):

1. Subscribe at **<https://apps.olausson.de/sync_provider_for_proton/checkout.html>**
   (monthly or yearly; 7-day free trial). The app's **Start 7-day free trial** button opens
   the same page.
2. After payment, the page shows your **subscription ID** (starts with `sub_`). Copy it.
   The **transaction ID** on Paddle's invoice (starts with `txn_`) works as well: the app
   looks up the subscription it paid for.
3. In the app, open **Settings**; on the **Subscription** card at the top, tap
   **Enter subscription ID**, paste the ID and tap **Activate**.
4. Keep the subscription ID somewhere safe, such as a password manager. You'll need it to
   re-activate after reinstalling or on another device. During the trial, and while a
   subscription is paused, unpaid or expired, the card keeps its **Enter subscription ID**
   button, and the dialog shows the ID this phone has stored.

Lost the ID? The transaction ID (`txn_`) on Paddle's invoice activates too. Otherwise open the
**Manage subscription** link in your Paddle receipt email (Paddle names the seller as BEO) and
copy the subscription ID from Paddle's page. A Google Play subscription does not unlock
this build: it only works in the Google Play version.

Manage or cancel anytime via the link in your Paddle receipt email.

## Updates & signing

APKs are signed with a stable key, and Android installs an update only if it carries the
same signature; Obtainium picks up new releases from this repository. This build uses a
**different signing key than the Google Play version**, so you can't switch between the two
without uninstalling first.

## Source code

The source is published from version 2.0.0 on. From then, each release tag points at the
commit holding that release's source; tags of earlier releases point at commits that hold
only a README. To compare, check out the tag of the version you have installed:
`git checkout v<version>`.

This repository is generated from the development repository at each release, so pull
requests cannot be merged here. Please report problems as issues instead.

### Building from source

You need a JDK to start the Gradle wrapper, and the Android SDK; the command-line tools
are enough once you have accepted their licences (`sdkmanager --licenses`). On the first
run Gradle downloads itself, the JetBrains JDK 21 it builds with, the SDK platform and
build tools it needs, NDK 27.3.13750724 (a large download, used to strip the prebuilt native
library), and the libraries.

1. Point Gradle at your SDK, either with the `ANDROID_HOME` environment variable or with
   a `local.properties` file containing `sdk.dir=/path/to/Android/Sdk`.
2. Build a debug APK of the version sold outside Google Play:
   `./gradlew assembleDirectDebug` (on Windows: `gradlew.bat assembleDirectDebug`).
   The APK lands in `app/build/outputs/apk/direct/debug/`.
3. Run the unit tests with `./gradlew testDirectDebugUnitTest`. Seven time-zone tests stand in
   for a phone with outdated rules, so they need the JDK's time-zone data to predate 2026d
   (JetBrains JDK 21.0.10 has 2025b); on a JDK with newer data they are skipped.

Release builds (`assembleDirectRelease`) come out unsigned, because the signing key is
not part of this repository. A build you make yourself is signed with a different key
from the published APK, so Android treats it as a different app and it cannot update an
installed copy.

OpenPGP runs in GopenPGP, a Go library, through a small binding that the build uses as the
prebuilt `app/libs/gopenpgpbridge.aar`. The binding's source and build script are in
`native/gopenpgp-bridge/`; GopenPGP and its dependencies are fetched at the versions its
`go.mod` pins.

## Licence

The source is available under the [PolyForm Shield License 1.0.0](LICENSE.md). You may
read, build, change and use it for any purpose except providing a product that competes
with it, or with another product its licensor provides using it; the licence text decides.
It is not an open-source licence.

Third-party components keep their own licences: see
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## Links

- Website: <https://apps.olausson.de>
- Privacy policy: <https://apps.olausson.de/privacy-policy.txt>
