package xyz.grtlabs.pilastri.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * Element TYPE tags, read by `tools/consistency-check.mjs` from `uiautomator dump` (the root sets testTagsAsResourceId, so each tag
 * is the node's resource-id). A tag names a type, never an instance: every element of one type carries the same tag. Tags have no
 * visual effect. Put the tag in the shared component of the type; see `ConsistencyTagsTest` for types still drawn ad hoc.
 */
object UiTag {
    const val PAGE_HEADER = "page-header"
    const val PAGE_TITLE = "page-title"
    const val PAGE_SUBTITLE = "page-subtitle"
    const val PERIOD_COVERAGE = "period-coverage"
    /** H6: the current period of a swiping page, applied only by PeriodTitle (PageHeader.kt); same height on Mese and Spese. */
    const val PERIOD_TITLE = "period-title"
    /** M1: the "giorno d/D" chip of a period header; moves with its page like the period-title (PageHeader.kt PeriodChip). */
    const val PERIOD_CHIP = "period-chip"
    /** M1: a subtitle that describes the selected period and is not the page-subtitle of the header holding the period-title. */
    const val PAGE_TRAILING = "page-trailing"
    const val CARD = "card"
    const val CARD_TITLE = "card-title"
    const val CARD_TRAILING = "card-trailing"
    const val SECTION_LABEL = "section-label"
    const val CHIP = "chip"
    const val PILL = "pill"
    const val PERIOD_LABEL_PREV = "period-label-prev"
    const val PERIOD_LABEL_CUR = "period-label-cur"
    const val PERIOD_LABEL_NEXT = "period-label-next"
    const val DOCK = "dock"
    const val ISLAND = "island"
    const val GEAR = "gear"
    const val PAGE_CONTENT = "page-content"
    const val PANE_LEFT = "pane-left"
    const val PANE_RIGHT = "pane-right"
    const val DOCK_ITEM = "dock-item"
    const val CHART = "chart"
    const val ACCENT_CARD = "accent-card"
    const val PRIMARY_FIGURE = "primary-figure"
    const val SECONDARY_FIGURE = "secondary-figure"
    const val STAT_LABEL = "stat-label"
    const val ATTENTION_ROW = "attention-row"
    const val CONTENT_ROW = "content-row"
    const val OVER = "over"
    const val SELECTED = "selected"
    const val ICON_DISC = "icon-disc"
    // Wave 2 contract (orchestrator, 2026-11-08): CARD_BODY = the padded content column inside CardKit's CardColumn (14 dp from the
    // card on every side); its first/last visible content must touch it. ACTION_ROW = the shared row of pills/buttons that never clips.
    // PERIOD_* = the month pager: the clip box and each page (neighbours keep these tags even without other semantics).
    const val CARD_BODY = "card-body"
    // R2a (wave 2): CARD_HEADER = the fixed 32 dp title row of a titled card (the first content of the card: the title line box is smaller and sits centred inside it, K3);
    // CARD_BODY_ROWS = the body overlay of a ROW card (rows carry their own 10 dp inset and 48 dp minimum height, so only the header is measured vertically);
    // SETUP_TITLE = the Setup flow title (board 8a, 30 sp, its own role, not a page-title).
    const val CARD_HEADER = "card-header"
    const val CARD_BODY_ROWS = "card-body-rows"
    const val SETUP_TITLE = "setup-title"
    const val ACTION_ROW = "action-row"
    const val PERIOD_VIEWPORT = "period-viewport"
    const val PERIOD_PAGE_PREV = "period-page-prev"
    const val PERIOD_PAGE_CUR = "period-page-cur"
    const val PERIOD_PAGE_NEXT = "period-page-next"
    /** The drawn tile of a page of a strip that has one (the hero month): on prev, cur and next, so a dump shows the neighbour's peek is drawn (CR-01, add-on peek-visible). */
    const val PERIOD_PAGE_SURFACE = "period-page-surface"
}

fun Modifier.cardBodyTag(): Modifier = testTag(UiTag.CARD_BODY)
fun Modifier.cardHeaderTag(): Modifier = testTag(UiTag.CARD_HEADER)
fun Modifier.cardBodyRowsTag(): Modifier = testTag(UiTag.CARD_BODY_ROWS)
fun Modifier.actionRowTag(): Modifier = testTag(UiTag.ACTION_ROW)

fun Modifier.pillTag(): Modifier = testTag(UiTag.PILL)
fun Modifier.cardTitleTag(): Modifier = testTag(UiTag.CARD_TITLE)
fun Modifier.cardTrailingTag(): Modifier = testTag(UiTag.CARD_TRAILING)
fun Modifier.pageContentTag(): Modifier = testTag(UiTag.PAGE_CONTENT)
fun Modifier.paneLeftTag(): Modifier = testTag(UiTag.PANE_LEFT)
fun Modifier.paneRightTag(): Modifier = testTag(UiTag.PANE_RIGHT)
fun Modifier.dockItemTag(): Modifier = testTag(UiTag.DOCK_ITEM)
fun Modifier.chartTag(): Modifier = testTag(UiTag.CHART)
fun Modifier.accentCardTag(): Modifier = testTag(UiTag.ACCENT_CARD)
fun Modifier.primaryFigureTag(): Modifier = testTag(UiTag.PRIMARY_FIGURE)
fun Modifier.secondaryFigureTag(): Modifier = testTag(UiTag.SECONDARY_FIGURE)
fun Modifier.statLabelTag(): Modifier = testTag(UiTag.STAT_LABEL)
fun Modifier.attentionRowTag(): Modifier = testTag(UiTag.ATTENTION_ROW)
fun Modifier.contentRowTag(): Modifier = testTag(UiTag.CONTENT_ROW)
fun Modifier.overTag(): Modifier = testTag(UiTag.OVER)
fun Modifier.selectedTag(): Modifier = testTag(UiTag.SELECTED)
fun Modifier.iconDiscTag(): Modifier = testTag(UiTag.ICON_DISC)
