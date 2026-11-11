package xyz.grtlabs.pilastri.ui.setup

/*
 * Setup's provider disclosure text. The bank authorization page is no longer ours (wave bank-browser-return: BankBrowser opens the
 * person's browser, BankReturn takes the return), so the in-app provider page, its navigator and its hand-off code are gone.
 */

internal const val EbDisclosureText = "Le banche si collegano tramite Enable Banking, un fornitore autorizzato PSD2 per l'accesso ai conti: " +
    "i movimenti passano da Enable Banking prima di arrivare sul telefono. L'account Enable Banking è tuo; la chiave dell'applicazione " +
    "resta nel Keystore del telefono. Ogni banca chiede la tua approvazione, e il consenso dura al massimo quanto consente la banca " +
    "(di solito 180 giorni). Puoi saltare questo passo: i conti restano n/d."
