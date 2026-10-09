<div align="center">

<h1><img src="docs/img/tryst-wordmark.svg" alt="Tryst" width="220" /><br /><img src="fastlane/metadata/android/en-US/images/icon.png" width="96" alt="Tryst app icon" /></h1>

### A private, local-only journal for your intimate life — encrypted, offline, and open source.

[![License: GPLv3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![F-Droid](https://img.shields.io/f-droid/v/app.tryst?logo=fdroid&logoColor=white&label=F-Droid)](https://f-droid.org/en/packages/app.tryst/)
[![Platform](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](#built-with)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)](#built-with)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](#built-with)
[![Offline only](https://img.shields.io/badge/network-none-critical)](#why-tryst)
[![Release](https://img.shields.io/badge/release-v0.6.0-success)](CHANGELOG.md)

Tryst keeps your most personal data on your phone and nowhere else — no account, no sync,
and **no internet permission at all**, so the app *cannot* send your data anywhere.
Inspired by other tracking apps you need to pay for, and built so privacy is the feature, not a footnote.

<a href="fastlane/metadata/android/en-US/images/phoneScreenshots/01_trysts_list.png"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01_trysts_list.png" width="18%" alt="Trysts list" /></a>
<a href="fastlane/metadata/android/en-US/images/phoneScreenshots/02_trysts_calendar.png"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02_trysts_calendar.png" width="18%" alt="Calendar heatmap" /></a>
<a href="fastlane/metadata/android/en-US/images/phoneScreenshots/03_photos_grid.png"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03_photos_grid.png" width="18%" alt="Photos gallery" /></a>
<a href="fastlane/metadata/android/en-US/images/phoneScreenshots/05_insights.png"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/05_insights.png" width="18%" alt="Insights" /></a>
<a href="fastlane/metadata/android/en-US/images/phoneScreenshots/06_partners.png"><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/06_partners.png" width="18%" alt="Partners" /></a>

<sub><i>Screenshots captured from a recent build on a synthetic dataset (no real data).</i></sub>

</div>

## Changes in this fork

Everything below is **on top of** upstream [`herbiewalker/tryst`](https://github.com/herbiewalker/tryst)
v0.6.0 — the rest of this README describes the original app. Upstream's
[zero-network promise](#why-tryst) is preserved: still no `INTERNET` permission, and the
`CheckNoNetworkPermissionTask` build guard is untouched.

### Fixed

**Backups could not be imported — and the error message hid why.**
Any restore failed, including one exported moments earlier, while the UI always claimed
"wrong password or not a Tryst backup". Two causes: `BackupViewModel.import` discarded
`e.message`, so a locked session or a truncated file was indistinguishable from a bad password
(the `export` path already reported it — an oversight, not a design choice); and
`suppressNextAutoLock` is consumed by the *first* backgrounding, which is the file picker, so
looking the backup password up in another app locked the session and the restore then died on
`Database accessed while locked`. The import now reports what actually went wrong, and the
suppression is re-armed after the picker returns.

*The crypto was ruled out by direct experiment*: a Tink 1.15.0 round-trip over the real container
— header layout, multi-segment payloads (1/3/8 MiB across the 1 MiB segment boundary), the
iteration-count bounds — all pass, `BackupManager.TABLES` matches Room schema v16 exactly, and the
repo's own `samples/tryst-test-dataset.tryst` decrypts (113 media blobs).

**Insights bar charts ignored their values.**
`VerticalBarChart` sized bars with `Modifier.weight(fraction)`, but `weight` is a *flex
distribution*, not a scaled length: it splits whatever space is left after the fixed-height
children (38 dp of labels and spacers) and shares it across every bar. With 12 monthly buckets the
tallest bar reached 14% of the track; on the heavily skewed rating histogram it computed to 454%.
The bars were squashed into a stub row. Now a fixed 96 dp track with `fillMaxHeight(fraction)` —
the same shape `RankedBars` already used correctly — and the value labels moved *inside* the track
so each number rides its own bar top instead of lining up on one horizontal line.

### Added

**Longest / Shortest duration tiles** on the Insights overview, after *Avg duration*, which navigate
straight to the encounter holding that extreme. Ties resolve to the more recent record. Backed by
5 new unit tests, including one asserting a scoped window never reports an encounter outside it.

**A preset export password**, so exporting no longer prompts every time. It is encrypted with a
**dedicated** Android Keystore key (AES-256-GCM, non-exportable) — deliberately *not* the vault's
KEK, because the preset must stay readable while the app is locked and the two secrets should not
share a lifetime. If the keystore refuses, the password simply isn't stored rather than falling
back to plaintext; if it later becomes unreadable (device keystore reset), it is cleared and the
prompt returns. The import path deliberately offers no "remember" — a password you only need to
*read* a backup is not worth storing. Editing the field never shows the stored value.

**A default export folder.** Pick it once via SAF; OneDrive and other providers appear as folder
choices and sync the file themselves, so no network permission is needed. The import picker now
**opens in that folder** too, since that is where the backups are. Filenames carry a
minute-resolution timestamp, because the one-tap export path has no save dialog to rename in and a
date-only name would silently overwrite the same day's earlier backup.

### Known issues left alone

Two upstream problems are documented rather than fixed, as both need a decision first:

- **Insights axis labels mix languages.** `monthLabel()` and `DayOfWeek.getDisplayName()` follow
  `Locale.getDefault()`, so on a Chinese system they read `1月` / `周一` while every tile label,
  category name (`"Anonymous"`, `"Solo"`) and section heading stays hardcoded English — the project
  ships no `values-zh`. Beyond looking wrong, it puts those axis labels outside `strings.xml`, so
  they cannot be localized at all. Either pin them to `Locale.ENGLISH` (consistent with the rest,
  small change) or move them into resources and add a proper translation (correct, larger).
  This is also why `InsightsEngineTest`'s two English-label assertions fail on a Chinese system —
  run tests with `-Duser.language=en`.
- **`samples/tryst-sample-backup.tryst` does not open** with the password its own
  `tools/dataset/README.md` documents (`Tryst-Test-2026`), while `tryst-test-dataset.tryst` does.
  The header is valid and the size is plausible, so the file itself disagrees with the docs.

### Repository note

Upstream commits `archive/v0.6.0/mapping.txt` (52 MB of R8 output from their release build). It is
kept here, since it is part of the inherited history — but note it describes a build of the
*upstream* code, so it does not match any APK produced from this tree. See
[archive/README.md](archive/README.md).

Full detail, including the reasoning behind each fix: [FORK-NOTES.md](FORK-NOTES.md).

## Why Tryst

Privacy isn't a setting here — it's the architecture.

- 🚫 **No network, ever.** The app declares no `INTERNET` permission, and a build-time CI guard
  fails the build if one ever sneaks in. Data physically cannot leak to a server.
- 🔒 **Encrypted at rest.** Entries live in an encrypted **SQLCipher** database; photos are
  **Tink AES-256-GCM** blobs in the app's private storage — never your gallery, MediaStore, or any cloud.
- 🔑 **Locked to you.** A 6-digit app PIN (separate from your phone's), optional biometric unlock,
  auto-lock the moment Tryst leaves the screen, and `FLAG_SECURE` to blank screenshots and the
  app-switcher preview. The key is derived from your PIN and double-wrapped by a hardware-backed
  Keystore key (StrongBox when available).
- 📵 **Zero tracking.** No analytics, ads, or crash-reporting SDKs of any kind — none are in the build.
- 📤 **Your data is yours.** The only way it leaves the device is a manual, password-encrypted
  backup you control. There is no password recovery, because there is no server.
- 🔍 **Verifiable.** GPLv3 and fully open source, so every promise above is auditable — and F-Droid
  builds it from this source.

## Features

- **Rich encounter logging** — date, time, duration, partners, acts, positions, protection, mood,
  place, occasion, toys, kinks, a 1–5 rating, orgasms, notes, and encrypted photo attachments.
- **Every category is yours** — Tryst ships **no predefined catalogs**; the handful of neutral starter
  entries (Kissing, Date night, …) are just editable rows, and you **add, rename, or remove your own**
  acts, kinks, positions, toys, occasions, and finish locations on a dedicated management page per
  category. Everything you add — or had already logged — counts fully across Insights and Achievements.
- **Partners & a self profile** — named or anonymous partners with relationship type and optional
  demographics (age, ethnicity, height, body type, location) and a **portrait album per person** (a set
  of photos you can rotate the current avatar from), plus your own profile.
- **Photos gallery** — a browsable Photos tab over every image attached to a tryst plus every person's
  portrait album, with layouts (date grid, mosaic, by-partner, People avatars, feed), search, filters,
  favourites, bulk actions, a slideshow, pinch-to-zoom viewer, and — the "add-to-person" action — one
  tap in the viewer to also file a photo under any partner (or You).
- **Insights** — a pure-Kotlin stats engine: totals, week streaks, averages, monthly & weekday
  trends, and per-attribute breakdowns. Reorder/hide tiles and cards, pick a chart style per card
  (bars / line / donut), with stable per-type colors. Charts are **hand-drawn** — no chart dependency.
- **Achievements** — dozens of milestones, streaks, and variety badges, derived from your log with
  progress bars and unlock dates. No extra storage.
- **Calendar** — a tonal activity heatmap with per-day markers, month/week toggle, and swipe to
  page. Land on it by default if you like.
- **Backup & import** — full-fidelity password-encrypted export/restore (photos included), plus a
  CSV importer with column mapping to bring history in from other apps.
- **Thoughtful polish** — light/dark themes + Material You, tablet/foldable adaptive layouts, a
  discard-changes guard so a stray tap never eats a half-finished entry, a type-to-confirm reset,
  and an in-app *What's new*.

## Built with

Kotlin · Jetpack Compose + Material 3 · Room over **SQLCipher** · Google **Tink** (media crypto) ·
Hilt · Coroutines/Flow. `minSdk 31` (Android 12) · `compileSdk`/`targetSdk 36`. **No networking
libraries at all**, and charts are hand-drawn in Compose — the dependency surface stays tiny and
fully FOSS. Architecture is MVVM + repository with a package-by-feature layout; the stats and
achievements engines are stateless and JVM-unit-tested. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/FLOWCHARTS.md](docs/FLOWCHARTS.md).

## Building

Full toolchain notes are in [docs/SETUP_WINDOWS.md](docs/SETUP_WINDOWS.md). The essentials:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug            # build the debug APK
.\gradlew.bat checkNoNetworkDebug      # anti-leak guard: fails if any network permission appears
.\gradlew.bat testDebugUnitTest        # JVM unit tests
.\gradlew.bat detekt ktlintCheck       # quality gates
```

Screenshots are black by design on-device (`FLAG_SECURE`).

## Status

✅ **Shipped.** Current release **v0.6.0** (versionCode 16, schema v16). Distribution is **F-Droid**,
which builds and signs from this source — Tryst ships no binary and commits no signing key. The
**0.6.x** line opens with **settings that survive a backup**: theme, Insights layout, and Photos
layout preferences now ride the encrypted export (container format v2, backward-compatible with
v1 backups) instead of resetting on a new phone, plus a color refresh that ties the app's palette
to its own launcher icon and a pass of button/layout polish (a clearer Save action, borderless
Settings rows, unified corner radii). The **0.5.x** line before it delivered the **Photos** tab: a
browsable gallery over every image attached to a tryst plus every person's portrait album, with
search, filters, favourites, bulk actions, a slideshow, and a pinch-to-zoom viewer with
add-to-person and set-as-avatar actions; a **full-screen partner editor** with a per-person photo
strip; **atomic encrypted-backup restore** with a "replace my data" checkbox default-on; **inline
add** for every catalog category directly from the encounter editor; and a raft of behaviour +
verbiage polish out of a formal 7-lens post-release audit
([docs/audits/2026-07-30-triage.md](docs/audits/2026-07-30-triage.md)). Everything you had already
logged is preserved across every schema bump (v13→v14→v15→v16, all additive-only).

Full release notes are in [CHANGELOG.md](CHANGELOG.md); the milestone history and pre-release audit
program live in [docs/ROADMAP.md](docs/ROADMAP.md), and post-1.0 plans in
[docs/ROADMAP_FUTURE.md](docs/ROADMAP_FUTURE.md).

## Documentation

| Doc | What |
|-----|------|
| [REQUIREMENTS.md](docs/REQUIREMENTS.md) | Functional & non-functional requirements |
| [THREAT_MODEL.md](docs/THREAT_MODEL.md) | Adversaries, mitigations, residual risk |
| [SECURITY_DESIGN.md](docs/SECURITY_DESIGN.md) | Encryption & key management |
| [DATA_MODEL.md](docs/DATA_MODEL.md) | Entities & fields (schema v16) |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Stack & module layout |
| [FLOWCHARTS.md](docs/FLOWCHARTS.md) | Visual maps of the core logic flows |
| [ROADMAP.md](docs/ROADMAP.md) | Milestones & the 12-pass pre-release audit |
| [ROADMAP_FUTURE.md](docs/ROADMAP_FUTURE.md) | Post-1.0 roadmap (shipped + planned) |
| [ROADMAP_VIDEO.md](docs/ROADMAP_VIDEO.md) | Video attachments — its own theme (needs a runtime dep) |
| [DECISIONS.md](docs/DECISIONS.md) | Decision log & open questions |
| [RELEASE.md](docs/RELEASE.md) | Cut-a-release checklist + F-Droid submission |
| [CHANGELOG.md](CHANGELOG.md) | Per-release notes |
| [SETUP_WINDOWS.md](docs/SETUP_WINDOWS.md) | Build & run on Windows |

## License

**GPLv3.** Tryst is free, open-source software under the
[GNU General Public License v3.0](LICENSE) — so anyone can verify the privacy promises above, and any
redistributed version must also be GPLv3 with source available. Third-party components and their
licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and in-app under
**Settings → About** (all GPLv3-compatible).
