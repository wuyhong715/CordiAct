#!/usr/bin/env node
import { mkdtempSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { packExtension } from '../src/pack.mjs';

const [manifest, output, ...extra] = process.argv.slice(2);
if (!manifest || !output || extra.length) {
  console.error('Usage: cordiact-pack <manifest.json> <extension.json>');
  process.exitCode = 1;
} else {
  try {
    if (resolve(manifest) === resolve(output)) throw new Error('The manifest and extension must have separate paths.');
    const artifact = packExtension(manifest);
    const staging = mkdtempSync(join(dirname(resolve(output)), '.cordiact-pack-'));
    try {
      const file = join(staging, 'extension.json');
      writeFileSync(file, artifact);
      renameSync(file, output);
    } finally {
      rmSync(staging, { recursive: true, force: true });
    }
  } catch (error) {
    console.error(`cordiact-pack: ${error.message}`);
    process.exitCode = 1;
  }
}
