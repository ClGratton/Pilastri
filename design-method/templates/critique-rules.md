# Critique rules: every UI critique the user made, as a checkable rule

Written <date> from <REQUESTS.md, project instructions, DESIGN.md, COUNCIL.md quotes>. Purpose: the automatic checker flags everything the user critiqued.

Conventions: layout unit = px / scale (Android dp: densityDpi/160; iOS pt: screen scale); tolerance default +-1 dp unless stated. Tags are test tags exposed to the accessibility dump (resource-id, accessibility id). New tags are marked (new). "Screens" = top-level pages, detail pages, setup and connector pages; size classes = <wide>, <narrow>.

Tags used: `<tag>`, `<tag>`, ...

## Kinds

| Kind | Checked by | Evidence |
|---|---|---|
| GEOMETRY | The checker/layout tests from node bounds (tolerance in dp) | Rows `screen / rule / element / measured / expected` |
| STRUCTURE | The checker from tags and text (counts, containment, presence) | Tag counts per screen |
| SOURCE | grep or unit test on code or manifest (literals, tokens, platform features) | Test name or grep |
| MOTION/UX | A reviewer with frames, recordings or dumps at fixed times | Frame times and what must be seen |

## Table

| ID | date | verbatim quote | what it means in the UI | kind | rule | screens | status today |
|---|---|---|---|---|---|---|---|
| <PREFIX>-01 | <date> | "<exact words>" | <element and screen named exactly> | GEOMETRY | <tag A>.left == <tag B>.left (+-1 dp) | <screens> | open / PASS (evidence) / exception (user decision) |
| <PREFIX>-01b | <date> | same | <companion aspect> | SOURCE | <grep or test> | <files> | open |

## Counts

Rows: <n>. By kind: GEOMETRY <n>, STRUCTURE <n>, SOURCE <n>, MOTION/UX <n>.

## Machine rules

One line per GEOMETRY / STRUCTURE / SOURCE rule; implement one by one; each reports tag, screen, size class, measured vs expected.

GEOMETRY:
- <PREFIX>-01: <tag> <relation> <tag> (+-<n> dp)

STRUCTURE:
- CR-<n>: exactly <n> `<tag>` per screen, containing `<tag>`

SOURCE:
- CR-<n>: no <literal pattern> outside <token file>

## Not turned into a machine rule

CR-<ids>: <why> and the exact frames/evidence a reviewer needs.
