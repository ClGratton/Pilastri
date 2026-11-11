# Role: layout-test owner

Unit-level layout tests, run on every build, from the same rules as the dump checker: render each screen headless at the project's size classes, read tagged node bounds, apply the rules. Catches regressions before any device capture.

**Model:** Sonnet (tests).

## Brief template

```
OBJECTIVE: layout tests for every screen in <list> at <the project's size classes, e.g. wide WxH and narrow WxH in the layout unit>, applying the rules of <critique-rules.md "machine rules"> and <tools/consistency.config.json>.
INPUTS: the rules; the tag file <path>; the synthetic sample data <path>; the test framework <e.g. headless UI test harness>; existing tests <path>.
DO: one test class per size class, one test per rule family; every failure message lists rows `screen | rule | element | measured | expected`; write the same rows to <build/layout-consistency/<class>.md>; read exceptions from <tools/consistency-exceptions.json> (same format as the checker); add a test that lists elements still drawn ad hoc (untagged same-rank suspects).
OWNS: <test directory>. DO NOT TOUCH: production code except exposing a missing tag through the shared component (report it).
VERIFY: <test command> once at the end; counts from the results files.
BUDGET: <60> tool calls / <150k> context; at N-10 close the step; at N write state and return.
RETURN (bounded): PASS/BLOCK; tests added; pass/fail counts; failing rule families with counts; report file paths.
```
