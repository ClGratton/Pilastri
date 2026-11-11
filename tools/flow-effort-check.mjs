#!/usr/bin/env node
// Project instance of design-method/scripts/addons/flow-effort.mjs (SKILL K31). On demand: node tools/flow-effort-check.mjs (exit 1 = findings). Manifest: tools/flow-steps.json.
// Self-test with the user's must-flag cases: node design-method/scripts/addons/flow-effort.mjs --self-test
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { run } from '../design-method/scripts/addons/flow-effort.mjs';
const here = path.dirname(fileURLToPath(import.meta.url));
process.exit(run(path.join(here, 'flow-steps.json')));
