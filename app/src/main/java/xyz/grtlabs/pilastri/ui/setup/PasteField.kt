package xyz.grtlabs.pilastri.ui.setup

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** What the clipboard says about its clip without reading the content: no toast, no access to the text. */
/** [urlScore]: on API 31+ the clip description's URL classification confidence when the system has finished classifying it, else null (unknown never vetoes). */
internal data class ClipInfo(val isText: Boolean, val timestampMs: Long, val urlScore: Float? = null)

/** The only door to the clipboard in the app's setup (concept "clipboard read" in tools/concept-owners.json); tests fake it. */
internal interface ClipboardSource {
    /** Description of the primary clip, or null when there is none. Reads no content. */
    fun describe(): ClipInfo?
    /** The text of the primary clip. This is the one content read. */
    fun text(): CharSequence?
    /** The clip's label (reads no content); null when unknown. Used to refuse our own copied question. */
    fun label(): CharSequence? = null
}

internal class AndroidClipboardSource(private val context: Context) : ClipboardSource {
    private val cm get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    override fun describe(): ClipInfo? = runCatching { cm.primaryClipDescription?.let { ClipInfo(it.hasMimeType("text/*"), it.timestamp, urlScoreOf(it)) } }.getOrNull()
    private fun urlScoreOf(d: android.content.ClipDescription): Float? =
        if (android.os.Build.VERSION.SDK_INT >= 31 && d.classificationStatus == android.content.ClipDescription.CLASSIFICATION_COMPLETE) d.getConfidenceScore(android.view.textclassifier.TextClassifier.TYPE_URL) else null
    override fun label(): CharSequence? = runCatching { cm.primaryClipDescription?.label }.getOrNull()
    override fun text(): CharSequence? = runCatching { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context) }.getOrNull()
}

/** Tests provide a fake; null means the real clipboard. */
internal val LocalClipboardSource = compositionLocalOf<ClipboardSource?> { null }

internal sealed interface PasteResult {
    data object Empty : PasteResult
    data class Wrong(val text: String) : PasteResult
    data class Valid(val link: String) : PasteResult
}

/** The paste rules, pure: what a text is, what it says to the person, and when a clip may be read without being asked. */
internal object PasteRules {
    const val VALID = "Link valido"
    const val EMPTY = "Niente da incollare: copia prima il link dall'e-mail"
    const val WRONG = "Non è il link di Enable Banking"

    fun classify(text: CharSequence?): PasteResult {
        val t = text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return PasteResult.Empty
        return EbSignInLink.fromClipboard(t)?.let { PasteResult.Valid(it) } ?: PasteResult.Wrong(t)
    }

    fun outcome(r: PasteResult): ActionOutcome = when (r) {
        is PasteResult.Valid -> ActionOutcome.Done(VALID)
        PasteResult.Empty -> ActionOutcome.Failed(EMPTY)
        is PasteResult.Wrong -> ActionOutcome.Failed(WRONG)
    }

    /**
     * On return to the app: a link is taken from the clipboard only when the clip is text AND newer than [requestedAtMs] (when this
     * sign-in e-mail was requested). An older clip is never read; the description is checked first and reads no content.
     */
    /** Below this URL confidence a classified clip is not the sign-in link (an unclassified clip is not vetoed). */
    const val MIN_URL_SCORE = 0.5f

    fun freshLink(src: ClipboardSource, requestedAtMs: Long?): String? {
        val at = requestedAtMs ?: return null
        val d = src.describe() ?: return null
        if (!d.isText || d.timestampMs <= at) return null
        if (d.urlScore != null && d.urlScore < MIN_URL_SCORE) return null   // the system classified it and it is not a URL: never read
        return (classify(src.text()) as? PasteResult.Valid)?.link
    }
}

/** A long link shows its start and its end when the field is not being edited; the stored text is never changed. */
internal class MiddleEllipsis(private val maxChars: Int = 30) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val len = text.length
        if (len <= maxChars) return TransformedText(text, OffsetMapping.Identity)
        val head = maxChars / 2; val tail = maxChars - head
        val shown = text.subSequence(0, head).toString() + "…" + text.subSequence(len - tail, len)
        val map = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = when { offset <= head -> offset; offset >= len - tail -> shown.length - (len - offset); else -> head }
            override fun transformedToOriginal(offset: Int) = when { offset <= head -> offset; offset > head + 1 -> len - (shown.length - offset); else -> head }
        }
        return TransformedText(AnnotatedString(shown), map)
    }
}

/** Tags of the sign-in link step (shared contract with the capture add-ons). */
internal object EbTag {
    const val WAIT = "eb_link_wait"
    const val PASTE_OPEN = "eb_link_paste_open"
    const val FIELD = "eb_paste_field"
    const val BUTTON = "eb_paste_button"
    const val OUTCOME = "eb_paste_outcome"
}
