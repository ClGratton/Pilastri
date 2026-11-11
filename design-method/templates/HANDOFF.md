# <Project>: current checkpoint

Update before any compaction and at every slice end. Newest note first.

## Note <YYYY-MM-DD HH:MM> (read first)

- **Done, with evidence:** <item>: <test counts / report path / screenshot path>. Distinguish source change, unit tests, built artifact, emulator install, real-device acceptance, publication: say which one is proven.
- **In progress:** <item>: files <paths>; state: compiles yes/no; what remains.
- **Agents:** running: <name, role, task, calls so far>; stopped: <name, why, where its state is>.
- **Next concrete step:** `<exact command>` by <role>.
- **Gate status:** checker <green/red, report path>; design reviewer <PASS/BLOCK>; UX reviewer <PASS/BLOCK>; audit PRESENT/DIFFERENT/MISSING = <n/n/n>.
- **Open questions for the user:** <question>.
- **Per slice (calls, peak context, usage at start and end, reason for any exception):** <row>
