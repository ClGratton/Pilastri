#!/usr/bin/env node
// Project instance of design-method/scripts/addons/service-docs.mjs (SKILL K27). On demand: node tools/service-docs-check.mjs (exit 1 = findings). Manifest: tools/service-docs.json.
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { run } from '../design-method/scripts/addons/service-docs.mjs';
const here = path.dirname(fileURLToPath(import.meta.url));
process.exit(run(path.join(here, 'service-docs.json'), path.resolve(here, '..')));
