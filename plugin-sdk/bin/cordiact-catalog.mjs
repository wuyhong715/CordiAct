#!/usr/bin/env node
import { createCatalogRelease, createCatalogRoot, validateCatalogPolicy } from '../src/catalog.mjs';

const [action, config, key, output, ...extra] = process.argv.slice(2);
const checkPolicy = action === 'check-policy' && config && !key;
if (!checkPolicy && (!['root', 'release'].includes(action) || !config || !key || !output || extra.length)) {
  console.error('Usage: cordiact-catalog <root|release> <config.json> <key.pem> <new-output-directory>');
  console.error('       cordiact-catalog check-policy <policy-file>');
  process.exitCode = 1;
} else {
  try {
    if (checkPolicy) validateCatalogPolicy(config);
    else {
      const build = action === 'root' ? createCatalogRoot : createCatalogRelease;
      build(config, key, output);
    }
  } catch (error) {
    console.error(`cordiact-catalog: ${error.message}`);
    process.exitCode = 1;
  }
}
