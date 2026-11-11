package xyz.grtlabs.pilastri.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.grtlabs.pilastri.data.ChangeLayer
import xyz.grtlabs.pilastri.domain.rules.RuleRowView

/** The second chip of a proposed rule row (board 5a-03): "<campo>: da verificare" for a law value whose source is not verified. */
class RuleFieldToVerifyTest {
    private fun view(layer: ChangeLayer, verified: Boolean) = RuleRowView(
        rowId = "row-1", label = "Valore", layer = layer, before = "—", after = "1", verified = verified, confirmable = verified,
        validFrom = null, sourceUrl = null, quote = null, origin = null, outputs = emptyList(), cards = emptyList(),
    )

    @Test fun anUnverifiedLawValueNamesTheFieldToVerify() =
        assertEquals("fonte: da verificare", ruleFieldToVerify(view(ChangeLayer.LAW, verified = false)))

    @Test fun aVerifiedLawValueShowsNoSecondChip() = assertNull(ruleFieldToVerify(view(ChangeLayer.LAW, verified = true)))

    @Test fun aPersonalValueIsADeclarationNeverAPackStatus() {
        assertNull(ruleFieldToVerify(view(ChangeLayer.PERSONAL, verified = false)))
        assertNull(ruleFieldToVerify(view(ChangeLayer.PERSONAL, verified = true)))
    }
}
