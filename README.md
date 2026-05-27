# Sync for Proton

**Sync for Proton** syncs your Proton Contacts and Proton Calendar into Android's
native contacts & calendar, so they work everywhere on your phone — the dialer,
lock screen, smartwatch, and any calendar or contacts app. No Google account required.

This repository hosts **signed APK releases** for installing outside the Google Play
Store (e.g. on GrapheneOS or any de-Googled Android). The app source is maintained
privately; this repo contains downloads only.

## Requirements

- Android 10 (API 29) or newer
- A Proton account
- A subscription — **7-day free trial**, then €2.39 / month or €23.99 / year (see *Subscribing* below)

## Install with Obtainium (recommended)

[Obtainium](https://github.com/ImranR98/Obtainium) installs and auto-updates apps
straight from GitHub releases. With Obtainium already installed, tap the button to add
this app in one step:

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22me.proton.sync%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fbolausson%2Fsync-for-proton%22%2C%22author%22%3A%22bolausson%22%2C%22name%22%3A%22Sync%20for%20Proton%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%7D%22%2C%22overrideSource%22%3Anull%7D"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Add to Obtainium" height="54"></a>

Or add it manually:

1. Install Obtainium.
2. Tap **Add App** and paste this repo URL:
   `https://github.com/bolausson/sync-for-proton`
3. Obtainium finds the latest release APK — tap **Install**.
4. Future updates show up in Obtainium automatically.

## Install manually

1. Download the latest `.apk` from the [Releases](https://github.com/bolausson/sync-for-proton/releases) page.
2. Open it on your device and allow installing from this source if prompted.

## Subscribing / activating

This build is paid via the web (no Google Play Billing):

1. Subscribe at **<https://apps.olausson.de/sync_provider_for_proton/checkout.html>**
   (monthly or yearly; 7-day free trial).
2. After payment, the page shows your **subscription ID** (starts with `sub_`). Copy it.
3. In the app: **Settings → Subscription → Subscribe / Manage**, paste the ID, tap **Activate**.
4. Keep the subscription ID somewhere safe (a password manager) — you'll need it to
   re-activate after reinstalling or on another device.

Manage or cancel anytime via the link in your Paddle receipt email.

## Updates & signing

APKs are signed with a stable key; Obtainium recognises updates by signature. This build
uses a **different signing key than the Google Play version**, so you can't switch between
the two without uninstalling first.

## Links

- Website: <https://apps.olausson.de>
- Privacy policy: <https://apps.olausson.de/privacy-policy.txt>
