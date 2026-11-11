# Role: critique cataloger

Turns every user critique into a row of the critique-rules file, so each can be checked by an algorithm or a reviewer.

**Model:** Sonnet (rule rows come from the user's words; an error here propagates to every check).

## Brief template

```
OBJECTIVE: every critique in <REQUESTS.md range / transcript> becomes a rule row; nothing dropped.
INPUTS: REQUESTS.md <dates>, the project instruction file, the spec (DESIGN.md), COUNCIL.md quotes; the existing critique-rules file <path>; the tag list <path>.
FOR EACH CRITIQUE: (1) verbatim quote with date; (2) what it means in the UI, naming the exact element and screen (read the remark against the picture; if two readings fit, list both and mark ASK); (3) kind: GEOMETRY (bounds), STRUCTURE (tags/text), SOURCE (grep/unit test), MOTION/UX (reviewer + frames); (4) the exact rule with numbers or relations; (5) screens; (6) status today (open / PASS with evidence / exception with the user's decision).
GROUPING: by element type, not by the element last reported; merge duplicates; companion rows get a "b" suffix.
OUTPUT: update <critique-rules.md>: the table, the "machine rules" section (one line per GEOMETRY/STRUCTURE/SOURCE rule with tag names; new tags marked "(new)"), the "not machine" list with the frames/evidence a reviewer needs, and the counts by kind.
CONSTRAINTS: edit only that file; do not interpret beyond the quote.
BUDGET: <40> tool calls / <120k> context; at N-10 finish the group in progress; at N return.
RETURN (bounded): PASS/BLOCK; rows added; counts by kind; ASK items; list of new tags the checker builder needs.
```
