# Android platform notes (an example platform; the method itself is platform-independent)

Moved out of `SKILL.md` §1 steps 0 and 1b. List the features the screens need, verify each, and record it in the design document's "Platform features" table as enabled and checked (SOURCE or device check) or not applicable with the reason.

- **Design language.** Material 3 Expressive is an option the user picks or declines when the palette and design language are fixed; it is never added later on top of approved boards.
- **Edge-to-edge.** Content under transparent system bars; insets owned once (one component owns them, nothing pads twice).
- **Predictive back.** Manifest opt-in; in-app pops driven by the gesture's progress, the same transition as the in-app back button.
- **Adaptive launcher icon** with a monochrome layer.
- **Promoted ongoing-progress notifications** for any long user-visible process.
- **Priority conversations** only for messaging.
- **Per-app language and dynamic colour** only if the palette decision allows.
- **Units and targets.** The layout unit is dp = px / (densityDpi / 160); pass `--scale densityDpi/160` to the checker. The minimum touch target is 48 dp (Material); other platforms: iOS 44 pt, WCAG 2.2 on the web 24 px. Set it as `tokens.minTouch`.
- **Dump format.** `uiautomator dump` XML, parsed by `scripts/adapters/uiautomator.mjs` (config `adapter: "uiautomator"`); tags are resource-ids.
- **Install.** `scripts/platform/android.sh` (adb): foreground check through `dumpsys activity`, `install -r`, `lastUpdateTime` verification.
- **Motion checks.** Animator scales (the user's value and 1.0) for recordings; reduced motion = animator scale 0.
