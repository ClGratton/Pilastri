# Role: implementation owner

One coherent slice of code with an exclusive set of files. Builds from the tokens table and shared components; never invents sizes, colours or motion.

**Model:** Sonnet (code, build, tests).

## Brief template

```
OBJECTIVE: <the slice, e.g. "screen X matches board X1..X3 in all states">
OWNS (exclusive files): <paths>      DO NOT TOUCH: <paths owned by others, tools/**, tests of other owners>
SPEC (every element, from the approved boards): <spec rows with IDs; list each element; states: normal/empty/partial/error; wide and narrow>
TOKENS: <path of the tokens table>; use only roles in it; a missing role is added to the table first.
COMPONENTS: use the shared <page header / card / card header / row / chip / accent card / figure>; a screen composes, never sets sizes, colours, paddings, fonts or motion literals.
MOTION: tokens from <motion token file>; co-moving parts share one transform; stable panes do not re-render.
TAGS: expose the test tags <list> on the elements the checker measures.
RESTORE: every flow in the slice is listed in <state-restore manifest> with its holder; per trigger (background, recreate, process, link) a restore test or a known_lost item (K28); typed input and step never in plain remember.
FLOW EFFORT: every flow in the slice is a row in <flow-steps manifest> (K31-K34): steps with actor, surface, kind; a step the system can do runs unseen or behind our own screen; a third-party page only for sign-in, strong authentication or a required consent; the documented method with the fewest person steps; one function, one path; a sign-in is the provider's documented sign-in for apps in the default browser, a third-party page inside the app only with the recorded source that no such flow exists (K32); every failure the provider's documentation names has a state on screen and an executed test, listed case by case (K33); a return goes to an address the app owns and is validated against the pending state kept outside screen memory (K34); run <flow-effort check> and list its findings.
VERIFY: <build command> once at the end; tests <command>; no build after each edit. Device or emulator (or simulator) only if <condition>.
BUDGET: <60> tool calls / <150k> context. At N-10 close the step you are on; at N write state (files, compiles or not, what remains) to <HANDOFF.md section> and return.
RETURN (bounded): PASS/BLOCK; per spec element PRESENT/DIFFERENT/MISSING (DIFFERENT with the reason); evidence paths; no diffs, no narration.
```
