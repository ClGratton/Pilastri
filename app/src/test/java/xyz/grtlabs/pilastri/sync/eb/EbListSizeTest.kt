package xyz.grtlabs.pilastri.sync.eb

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-11-10 22:40 phone run: after the registration the applications list was longer than 2000 characters, the page script cut the
 * answer at 2000 before parsing and reported "list shape not recognised" (shape text=2000). The real STATUS script runs under node against a
 * fake page whose list answer is far longer than 2000 characters and must read it whole.
 */
class EbListSizeTest {
    private val runner = """
import fs from 'node:fs';
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const JWT = 'a.b.c';
const filler = 'x'.repeat(400);
const list = [];
for (let i = 0; i < 12; i++) list.push({ kid: 'other' + i, active: false, note: filler });
list.push({ kid: 'app-1', active: true, whitelisted_accounts: [{ uid: 1, bank: 'Isybank' }, { uid: 2 }] });
const body = JSON.stringify(list);
if (body.length < 4000) throw new Error('fixture too short');
const store = { getters: {}, state: {}, dispatch: async () => ({ token: JWT }) };
const document = { querySelector: (s) => (s === '#app' ? { __vue__: { ${'$'}store: store } } : null), querySelectorAll: () => [] };
const fetch = async () => ({ status: 200, ok: true, headers: { get: () => 'application/json' }, text: async () => body, json: async () => JSON.parse(body) });
const src = fs.readFileSync(process.argv[2], 'utf8');
const fn = new AsyncFunction('ARGS', 'document', 'fetch', 'location', src);
const r = await fn({ appId: 'app-1', bank: 'isybank' }, document, fetch, { pathname: '/cp/applications' });
console.log(JSON.stringify(r));
process.exit(r.active === true && r.linked === 2 && r.here === 1 ? 0 : 1);
"""

    @Test fun aListLongerThanTheCapIsReadWhole() {
        val dir = java.nio.file.Files.createTempDirectory("eb-list").toFile()
        try {
            File(dir, "STATUS.js").writeText(EbPanelScripts.STATUS)
            File(dir, "run.mjs").writeText(runner)
            val p = try { ProcessBuilder("node", File(dir, "run.mjs").path, File(dir, "STATUS.js").path).redirectErrorStream(true).start() }
            catch (e: java.io.IOException) { throw AssertionError("node is required: ${e.message}") }
            val out = p.inputStream.bufferedReader().readText()
            assertTrue("timed out", p.waitFor(60, TimeUnit.SECONDS))
            assertEquals("STATUS on a long list failed:\n$out", 0, p.exitValue())
        } finally { dir.deleteRecursively() }
    }
}
