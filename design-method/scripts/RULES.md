# Rules of `consistency-check.mjs`

The BASE holds generic algorithms; language, domain, platform and app specifics are add-ons (`addons/`, listed in `config.addons`), adapters (`adapters/`, `config.adapter`) or config data. Every rule type is switched on by `checks.<key>`, takes its tags from `tags.<role>` and its values from `tokens.<name>`. An enabled check whose token is missing, or whose key no base check or listed add-on knows, exits 2. Criteria are the ids of SKILL.md section 2. `--self-test` runs a clean scene plus one mutation per row (fixtures in `fixtures/selftest/`, written by `selftest.mjs`; its configs are self-test-only).

**Report line format** (the install gate parses it): `Dumps: N (dir), scale S, density S, tolerance +-T dp, measurements M, motion checks K.` `scale` = pixels per layout unit (`--scale`; `--density` and `--dpi` are accepted aliases; `density` in the report is the deprecated alias of `scale`). A `## Checks skipped` section lists every enabled check that could not run (no tag configured for its roles) or was allowed to find nothing (`config.allowAbsent`); `--strict` exits 1 when it is not empty. An enabled check whose tags are configured but never appear in any dump FAILS with `nothing checked: ...` (never a silent pass).

## Base checks

| Rule type (`checks` key) | Rule names in the report | Criterion | Tags (roles) | Tokens / other config keys |
|---|---|---|---|---|
| `cardInset` | card-title top / left inset, height; card-header centres; card-inset bottom / left / right | K3 | container, cardTitle, cardTrailing, chip, pill, chart, iconDisc | cardInset; `excludeRoles.cardInset`; `floating` |
| `cardBody` | card-body inset on 4 sides; first / last content touches body | K3, K4 | container, cardBody, cardBodyRows, cardHeader, cardTitle | cardInset, headerRowH; `excludeRoles.cardBody` |
| `clip` | text box wider than its pill / chip / card; text narrower than its label (heuristic) | K7 | pill, chip, container (+ `clipBoxRoles`) | heuristics `clipCharWidth`, `clipMaxLineH`, `clipMinChars`, `clipGuardMaxW` |
| `overlap` | two texts overlap | K1 | none (`excludeRoles.overlap`) | none |
| `pageHeader` | page-header title / subtitle y, x, height; setup title; capture cut | K1, K2 | pageTitle, pageSubtitle, pageContent, setupTitle | `naming.topMarker`, `naming.setupMarker` |
| `labels` | label section left minus card left | K4 | sectionLabel, container | none |
| `cardEdges` | card-edge right per pane (adds back the inset adjuster of a viewport) | K4, K5 | container | `insetAdjust` |
| `chipHeight` | chip height | K1 | chip, pageContent | none |
| `firstContent` | page-header content top (top-level pages) | K2 | pageContent | `naming.topMarker` |
| `touch` | clickable at least minTouch x minTouch | K7 | none (floating overlays: `floating`) | minTouch (the PLATFORM minimum: Android 48 dp, iOS 44 pt, WCAG web 24 px) |
| `copy` | visible text longer than N characters | K9 | none | maxCopy |
| `gutterGap` | block edges on the gutter, gap between stacked blocks | K5 | container (+ `blockRoles`) | gutter, gap; `insetAdjust`; heuristics `blockMinRatio` |
| `duplicate` | BASE, whole screen, language-free: identical normalised text in two nodes; the same digit token with its attached unit in two nodes. No categories, no exemptions in code | K8 | every visible text node | `duplicateFilters`; `duplicateScreens` / `duplicateScope` (screen globs checked; a wide two-pane layout is not checked); `locale` (stop words, extra unit signs) |
| `dateRanges` (add-on of `duplicate`) | a date range and its own endpoint, or a sentence date and the same date written bare, are one fact. Off unless enabled | K8 | none | `dateLocale` (key of `locales.json`, or an inline object); warns when unset |
| `currencySplit` | a visible text (the text attribute, not the accessibility description) with a digit and a currency symbol joined by a breakable U+0020 space: it may wrap between them and leave the symbol alone; use U+00A0 / U+202F | K10 | every visible text node | `currencySymbols` (default € $ £; either order) |
| `coMotion` | children displaced like their container (rest / moving pairs) | M1 | container, cardTitle, cardTrailing (`coMotion.children`) | `clipRoles`; `naming.restMarker`, `naming.movingMarker` |

## Generic mechanisms (base; arrays of rule objects in the config, each names its roles; optional `id` prints the project's id)

| Key | Rule object | Rule name | Criterion |
|---|---|---|---|
| `singleton` | `{role, scope: "screen"|"pane", min?, max?, containedIn?}` | singleton: at most N role per scope | H1, C1 |
| `heightRatio` | `{role, over, min}` | ratio: role height >= min x over | H1, H2 |
| `heightEquals` | `{role, dp}` | height: role is N dp | C5 |
| `forbidInside` | `{role, child: "clickable"}` | forbid: no clickable inside the role | C5 |
| `alignRules` | `{role, layouts?, exactly?, centreX?, fromRightEdge?}` | align: role centre x equals screen centre / N dp from the right edge | K6 |
| `measure[]` (old name `rules[]`) | `{type: inset|size|position|alignBelow, tag, ...}` | custom majority measurements on any tag (a tag ending in `*` is a prefix) | K1, K3, K4 |

Each applies to every dump unless `topOnly: true`; a rule whose role never appears fails as "nothing checked".

## Add-ons (`config.addons`; each add-on's checks need it listed, else exit 2)

| Add-on | Checks | What it holds | Tokens |
|---|---|---|---|
| `frame-dock-island` | `frame`, `panes` | one design's chrome: one centred dock with N items on narrow, an island at a fixed right-edge distance, centred, plus gear on wide; panes split at the hinge, both populated | islandEdge, islandCentreTol, dockItems, paneMinTexts, paneSplitTol; heuristics `gearTol` |
| `pager-peek` | `peek`, `periodLabels` | the neighbour page of a pager is visible in the gutter margin = gutter - gap dp (optional overrides peekWide / peekNarrow); period labels sit on the same gutter line as the cards (no narrowed edge, no inset adjuster since 2026-11-09; a narrowed card or label is a deviation) | gutter, gap, accentPad; heuristics `labelMinWidth` |
| `pager-period` | `period`, `periodMotion` | a swipe-by-period page exposes `periodTitle` of equal height; period label moves like its page; period title moves like the current page (`periodPageCur`, default `periodPagePrefix`+"cur"); so does the whole period header (`periodHeader`: `header` role default `pageHeader`, `headerMembers` default `[pageSubtitle]` counted only inside the header node holding the title, `members` default `[periodChip, periodSubtitle]`; roles without a tag are skipped; absent when moving is a `capture:` row); vacuity guard | none |
| `hierarchy-opinions` | `primaryFigure`, `accent`, `figureScale`, `attention` | board-specific forms: accent card per pane on `hierarchy.accentScreens` / `accentBothPanes`, primary figure inside it and in the upper `figureTopRatio`, line-box ratios, attention row height with no button inside | figureTopRatio, ratio*, attentionH; heuristics `primaryTopTol` |

An add-on is a file `addons/<name>.mjs` exporting `{ name, checks: {key: [tokens]}, roles: {key: [roles]}, screen(ctx, s, helpers), pair(ctx, A, B, name, push)?, adjusters(ctx)? }`.

## Config blocks

- `adapter`: `uiautomator` (Android XML), `json-nodes` (`{nodes:[{id,text,clickable,scroll,bounds:[l,t,r,b]}]}`), or a new `adapters/<name>.mjs` exporting `{ext, parse(file, dp)}`; `dumpExtension` overrides the file extension.
- `naming`: `layoutSeparator` (default `-`), `topMarker`, `setupMarker`, `fontScaleMarker`, `fontScaleLabel`, `restMarker`, `movingMarker`. Defaults are empty: no top pages, no setup flow, no font-scale dumps. Dump names are `<layout><separator><screen>...`.
- `ruleIds`: `{ruleKey: "ID"}` appended to the neutral rule name, e.g. `" (CR-65)"`. Keys: cardBody, clip, overlap, firstContent, touch, copy, gutterGap, duplicate, peek, period, periodMotion, coMotion, primaryFigure, accent, figureScale, attention, frameDock, frameIsland, frameGear, panes.
- `floating` (roles drawn over the content: dock, island, ...), `blockRoles`, `clipBoxRoles`, `clipRoles`, `excludeRoles` (per rule, role names; a name ending in `Prefix` matches by tag prefix), `insetAdjust` (`[{role, perSideDp: n | {wide, narrow}}]`), `allowAbsent` (check keys allowed to find nothing, listed in the report).
- `heuristics` (every magic number, defaults in brackets): `scrollViewportMinH` [0.4] height share of the screen for a vertical scrollable to count as the viewport; `peekMinRatio` [0.4] cards narrower than this share of the widest are clipped neighbours and not measured; `sideInsetFull` [0.98] a node this wide says nothing about the side inset; `rightInsetReach` [30 dp] only content ending within this distance of the card edge measures the right inset (a design assumption); `primaryTopTol` [4 dp] a primary figure this close to its card top is cut by the viewport; `blockMinRatio` [0.4] width share for a block to count in gutter and gap rules; `labelMinWidth` [16 dp] narrower peeking labels are skipped; `clipCharWidth` [0.28], `clipMaxLineH` [28 dp], `clipMinChars` [4], `clipGuardMaxW` [0.8] the text-narrower-than-its-label heuristic; `gearTol` [2 dp] frame add-on.
- `locale` / `dateLocale`: see `locales.json` (`_template` shows the shape; stopWords, unitSigns, order, separators, months, full).

Majority rules (expected value = the most common one across screens, tolerance +-1 dp by default) are the measurements; token rules compare with a token. **Exceptions** (`consistency-exceptions.json`): `{rule (substring), screen (substring, or glob when it contains *), text (substring), reason, decidedBy, date}`; each needs the user's decision.

**Duplicate filters** (`duplicate-filters.json`, see `duplicate-filters.example.json`): a JSON array of `{ "screen": glob, "id": tag (optional), "text": regex (optional), "reason": why this ONE element legitimately repeats, "decidedBy", "date" }`. A filter names one element, never a whole type or all rows. The report prints `## Duplicate filters applied` with the number of elements each filter silenced and the screens, so a filter can never hide a row silently; run without the file to see the raw hits. A language-, domain- or app-specific check is an add-on enabled in `checks`, never a branch of the base.

## Text normalisation and per-line heights (wave 7)

- **Unicode spaces**: U+00A0, U+202F and U+2007 are read as U+0020 by every text match (duplicate comparison and filters, value tokens, exception matching); the raw text is kept for `currencySplit` only. This is a normalisation, not a loosening.
- **Per-line heights above the wrap scale**: dumps named with `naming.fontScaleMarker` are captured at `naming.fontScaleValue` (default 2). When that exceeds `heuristics.wrapAboveScale` (default 1.3), the roles in `wrapRoles` (default `pageSubtitle`, `pageTrailing`, `chip`) are compared by height per line (box height / line count; line count = round(height / the smallest single-line height of that role at that layout and scale)) in `pageHeader` height rows and in the pager-period "tallest text" rule. Roles not listed (page title, period title) stay one line (K2) and keep the box height. Below the threshold nothing changes.
