package xyz.grtlabs.pilastri.ui.setup

import org.json.JSONArray
import org.json.JSONObject
import xyz.grtlabs.pilastri.sync.eb.EbPanel
import xyz.grtlabs.pilastri.sync.eb.EbPanelResult

/*
 * The Enable Banking sub-flow across a process kill or an update: stack, banks (chosen, queue, asked, usage type, linked) and
 * checks. Never written: the e-mail, tokens, keys, link codes, session ids, a bank page address, working, failure, timers.
 */
object EbFlowCodec {
    fun encode(m: EbFlowModel): String = JSONObject()
        .put("v", 1)
        .put("stack", JSONArray(m.stack.map { JSONObject().put("s", it.screen.name).put("b", it.bank ?: JSONObject.NULL).put("r", it.round) }))
        .put("checks", JSONArray(m.checks.map { it.name }))
        .put("chosen", JSONArray(m.chosen)).put("queue", JSONArray(m.queue)).put("asked", JSONArray(m.asked))
        .put("psu", JSONObject().apply { m.psu.forEach { (b, t) -> put(b, t) } })
        .put("linked", JSONObject().apply { m.linked.forEach { (b, n) -> put(b, n ?: -1) } })
        .put("round", m.round)
        .put("open", m.consentOpen)
        .toString()

    /**
     * The restored flow, or null when the text cannot be read (the flow then starts normally).
     * The e-mail is not stored, so the e-mail-sent step and the registration step fall back to the e-mail step; a bank step keeps
     * its place but never a page: [needsStatusCheck] tells the host to ask the provider before showing it.
     */
    fun decode(text: String): EbFlowModel? = try {
        val o = JSONObject(text)
        if (o.optInt("v") != 1) throw IllegalArgumentException()
        fun strings(k: String): List<String> = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
        val stack = o.getJSONArray("stack").let { a ->
            (0 until a.length()).map { i ->
                val s = a.getJSONObject(i)
                EbStep(EbScreen.valueOf(s.getString("s")), if (s.isNull("b")) null else s.getString("b"), s.getInt("r"))
            }
        }
        require(stack.isNotEmpty() && stack.first().screen.let { it == EbScreen.EMAIL || it == EbScreen.BANKS })
        val queue = strings("queue")
        val m = EbFlowModel(
            stack = stack, checks = strings("checks").map { EbCheck.valueOf(it) }.toSet(), chosen = strings("chosen"), queue = queue,
            asked = strings("asked"),
            psu = o.getJSONObject("psu").let { p -> p.keys().asSequence().associateWith { p.getString(it) } },
            linked = o.getJSONObject("linked").let { l -> l.keys().asSequence().associateWith { k -> l.getInt(k).takeIf { it >= 0 } } },
            round = o.getInt("round"), consentOpen = o.optBoolean("open"),
        )
        when (m.screen) {
            EbScreen.LINK, EbScreen.REGISTER -> {
                val i = m.stack.indexOfLast { it.screen == EbScreen.EMAIL }
                if (i >= 0) m.copy(stack = m.stack.take(i + 1)) else EbFlowModel(round = m.round)
            }
            EbScreen.APPROVE, EbScreen.CONFIRM -> if (m.top.bank != null && m.current == m.top.bank) restoredConsent(m) else null
            else -> m
        }
    } catch (_: Exception) { null }

    /** A restore at EB-5 asks the provider before it shows anything: it never reopens a bank page from stored state. */
    private fun restoredConsent(m: EbFlowModel): EbFlowModel = EbFlowRules.restoredConsent(m).also {
        if (it !== m) xyz.grtlabs.pilastri.sync.eb.EbLog.line(xyz.grtlabs.pilastri.sync.eb.EbLog.EB, "restore consent status=in_progress->failed")
    }

    fun needsStatusCheck(m: EbFlowModel): Boolean = m.screen == EbScreen.APPROVE && m.current != null

    /**
     * The status check of a resumed EB-5 (no bank page, no stored address). The provider's answer decides: the application is
     * active and shows more linked accounts than the banks this run has linked -> onward to EB-6; anything else (not linked, not
     * signed in, unreachable) -> the approve step from its start, nothing failed yet.
     */
    suspend fun resumeApprove(panel: EbPanel, access: BankAccess, bankOf: String?): (EbFlowModel) -> EbFlowModel {
        val stay: (EbFlowModel) -> EbFlowModel = EbFlowRules::idle
        val appId = try { access.applicationId() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null } ?: return stay
        val bank = bankOf ?: return stay
        val s = try { panel.status(appId, bank) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { return stay }
        val session = try { access.hasSession(bank) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
        val already = s is EbPanelResult.Ok && EbFlowRules.alreadyLinked(s.value, session)
        xyz.grtlabs.pilastri.sync.eb.EbLog.line(xyz.grtlabs.pilastri.sync.eb.EbLog.EB, "step5 resume status=${if (s is EbPanelResult.Ok) "ok" else "failed"} session=${xyz.grtlabs.pilastri.sync.eb.EbLog.yn(session)}" + if (already) " skip=already-linked" else "")
        return if (already) { m -> if (bank !in m.noSkip) EbFlowRules.accountsLinked(m) else EbFlowRules.idle(m) } else stay
    }
}
