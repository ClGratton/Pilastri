# <Project>: design (the single living spec)

Process and criteria: `design-method/SKILL.md`. Corrections amend a row in place; they are never stacked as delta lists. Every UI brief cites these rows.

## Palette and design language

| Role | Light | Dark | Contrast pair checked |
|---|---|---|---|
| Accent | | | |
| Text on accent | | | |
| Tint | | | |
| Outline | | | |
| Surface / page background | | | |
| Ink / secondary ink | | | |
| Warning / error | | | |

Design language: <chosen with the user; the design-language variant if the platform offers one (e.g. Material 3 Expressive on Android) yes/no, and the date decided>.

## Tokens (measured from the approved boards; the single token source)

A style not listed here is not used on a screen: add the role here first, then in code.

| Role | Size / weight, colour | Notes |
|---|---|---|
| Page title | | one line |
| Page subtitle | | exactly one line, reserved |
| Primary figure | | one per screen |
| Secondary figure | | |
| Label / caption | | |
| Card title | | title row fixed height |
| Chip | | |
| Accent card | fill, radius, height, padding | one per screen with a primary figure |
| Content card | fill, radius, outline | |
| Attention row | | only when it applies |
| Rhythm | page padding, gap between blocks, header height | |

## Platform features (method SKILL step 1b; checked <date>)

| Feature | Status in the app | Evidence / decision |
|---|---|---|
| Palette by role | | |
| Design language | | |
| Edge-to-edge | | |
| Predictive back | | |
| Adaptive / themed icon | | |
| Ongoing-progress notification for long processes | | |
| Priority conversations | N/A unless messaging | |
| Per-app language / dynamic colour | | |

## Global frame (G)

Navigation, attention places (a fixed list), page header, wide-layout structure.

| ID | Element and behaviour | Source / status |
|---|---|---|
| G-01 | <element: placement, size, behaviour, motion> | <board ref, user quote with date; PRESENT/DIFFERENT/MISSING> |

## Screen specs

One table per screen, every element of every approved board.

### <Screen name> (boards: <paths>)

| ID | Element | Board shows | States (empty / partial / error) | Motion | Source / status |
|---|---|---|---|---|---|
| S1-01 | <element> | <what, where, which token roles> | <how each state looks; unknown is "n/d", never 0> | <tokens> | <PRESENT / DIFFERENT (reason) / MISSING> |

## Recorded deviations

| Date | Screen/ID | Deviation | Reason | Decided by (accepted / pending) |
|---|---|---|---|---|
