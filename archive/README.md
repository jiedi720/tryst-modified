# archive/

`v0.6.0/mapping.txt` is inherited from upstream [`herbiewalker/tryst`](https://github.com/herbiewalker/tryst),
who commit it as the R8 obfuscation mapping from their v0.6.0 release build — 52 MB of generated text.

It is left in place because it is part of this repository's inherited history, and deleting it would
only add a pointless 52 MB deletion to the diff.

Worth knowing when reading it: the mapping describes **upstream's** v0.6.0 build. This fork has since
changed `InsightsCharts.kt`, `InsightsScreen.kt`, `BackupViewModel.kt` and `SettingsScreen.kt`, so the
mapping does not correspond to any APK produced from this tree — it is only useful for de-obfuscating
the upstream release APK. Nothing here builds or tests against it.