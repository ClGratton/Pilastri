# Role: checker builder

Makes the machine rules run: test tags in code, the checker config, and source (grep/unit) tests. Starts from `scripts/consistency-check.mjs` and adapts it.

**Model:** Sonnet (code and tests).

## Brief template

```
OBJECTIVE: every GEOMETRY, STRUCTURE and SOURCE rule in <critique-rules.md "machine rules"> runs automatically and reports tag, screen, measured vs expected.
INPUTS: critique-rules file <path>; scripts/consistency-check.mjs and consistency.config.example.json from the method package; the app's tag file <path> (or where to create it); an existing capture folder <path> (for a first run).
DO: (1) add the missing tags to the shared components only (not to screens) and expose them to the accessibility dump; (2) copy the checker into <tools/>, write <tools/consistency.config.json> (container tag, rules, wideLayouts, topMarker, tolerance) from the rules; add rule types in the script if a rule needs one; (3) write source tests for SOURCE rules (token-only literals, motion tokens, colour roles, platform-feature manifest entries); (4) run on <capture folder>, check each deviation is real or record an exception with a reason; (5) document the capture procedure in <tools/README.md>.
OWNS: <tools/**, the tag file, shared component files for tags only>. DO NOT TOUCH: screens' layout code, other owners' files.
NEVER: loosen a tolerance or add an exception to make a rule pass without the user's decision.
BUDGET: <60> tool calls / <150k> context; at N-10 close the step; at N write state to <HANDOFF.md> and return.
RETURN (bounded): PASS/BLOCK; rules implemented vs total by kind; deviations found on the first run (count by rule); files changed.
```
