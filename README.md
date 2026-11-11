# Pilastri
Pilastri is a personal finance app for Android (Kotlin, Jetpack Compose) for people living in Italy.
It is a work in progress and has not been released.

## Folders
- `app/`: the Android application (sources, unit tests, resources, rule pack and bank list).
- `tools/`: project scripts, checkers and their fixtures.
- `design-method/`: the generic design and review method package the tools derive from.
- `site/`: the static pages served for the app (privacy, terms, bank return, app links).

## Build

`./gradlew :app:assembleDebug` (unit tests: `./gradlew :app:testDebugUnitTest`).

## Licences

Third-party licences are in `app/src/main/assets/licenses/`.
No licence has been chosen for the project's own code yet, so all rights are reserved.

## Not in this repository

The project's working documents and some device fixtures are not published, so some checks do not run from a clone:
tests reading `AGENTS.md` (`AutoSyncTest`, `ReducedMotionTest`, `LayoutConsistencyTest`) or `DESIGN.md` (`SourceRulesTest`,
`ConsistencyEngine`), and the tools `consistency-check.mjs`, `install-fold.sh`, `flow-steps.json`, `device-addons.json`
and `eb-script-harness.mjs`, which refer to those documents.
