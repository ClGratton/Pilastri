#!/usr/bin/env node
// Project instance of design-method/scripts/addons/state-restore.mjs (SKILL K28). On demand: node tools/state-restore-check.mjs (exit 1 = findings). Manifest: tools/state-restore.json.
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { run } from '../design-method/scripts/addons/state-restore.mjs';
const here = path.dirname(fileURLToPath(import.meta.url));
process.exit(run(path.join(here, 'state-restore.json'), path.resolve(here, '..')));
