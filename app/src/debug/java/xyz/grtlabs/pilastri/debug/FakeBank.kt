package xyz.grtlabs.pilastri.debug

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import xyz.grtlabs.pilastri.BuildConfig
import xyz.grtlabs.pilastri.sync.eb.AndroidEbAppKey
import xyz.grtlabs.pilastri.sync.eb.EbLocal
import xyz.grtlabs.pilastri.sync.eb.EbStateStore
import xyz.grtlabs.pilastri.ui.setup.AccessState
import xyz.grtlabs.pilastri.ui.setup.BankLink
import xyz.grtlabs.pilastri.ui.setup.BankReturn
import xyz.grtlabs.pilastri.ui.setup.ListState
import xyz.grtlabs.pilastri.ui.setup.SetupEntry
import xyz.grtlabs.pilastri.ui.setup.SetupModel
import xyz.grtlabs.pilastri.ui.setup.SetupProgressStore
import xyz.grtlabs.pilastri.ui.setup.SetupStep

/*
 * Debug-only proof means for the bank return (tools/fake-bank-provider.mjs is the provider). Not in the fast/release variants.
 * FakeBankSeedActivity: gives the app a registered fake application and a setup waiting at the bank step (no provider call);
 * `adb shell am start -n xyz.grtlabs.pilastri/.debug.FakeBankSeedActivity`, then launch MainActivity.
 */
class FakeBankSeedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bank = intent.getStringExtra("bank") ?: "Fake Bank"
        AndroidEbAppKey().create()
        EbStateStore.createOrNull(this)?.save(EbLocal(applicationId = "11111111-2222-4333-8444-555555555555", redirectUrl = BankReturn.ADDRESS))
        getSharedPreferences("pilastri_setup", MODE_PRIVATE).edit().clear().apply()
        SetupProgressStore.of(this).saveSetup(SetupModel(
            entries = listOf(SetupEntry.Step(SetupStep.BENVENUTO), SetupEntry.Step(SetupStep.CONSENSI)),
            access = AccessState.REGISTERED, list = ListState.READY, chosenBanks = listOf(bank), links = mapOf(bank to BankLink.IN_ATTESA),
        ))
        android.util.Log.i("FakeBank", "seeded bank=$bank base=${BuildConfig.EB_BASE_URL}")
        // Self-check (debug): registered and the fake provider's list, with the exception class when it fails.
        Thread {
            val a = xyz.grtlabs.pilastri.ui.setup.EbBankAccess(applicationContext)
            try { android.util.Log.i("FakeBank", "registered=${kotlinx.coroutines.runBlocking { a.registered() }} banks=${kotlinx.coroutines.runBlocking { a.banks() }.size}") }
            catch (e: Throwable) { android.util.Log.i("FakeBank", "selfcheck failed ${e.javaClass.simpleName} ${e.message}") }
            runOnUiThread { finish() }
        }.start()
    }
}

/** A fake bank app in its own process. Opened from the bank page; after 1.5 s it "approves" by opening the return link with a plain VIEW, like a real bank app. */
class FakeBankAppActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.data?.getQueryParameter("id") ?: ""
        setContentView(TextView(this).apply { text = "Fake bank app: approving $id"; textSize = 22f; setPadding(48, 200, 48, 48) })
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.EB_BASE_URL}/bank/approve?id=$id")).addCategory(Intent.CATEGORY_BROWSABLE))
        }, 1500)
    }
}
