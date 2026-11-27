import { generateKeyPairSync } from 'node:crypto';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createCatalogRelease, createCatalogRoot } from '../src/catalog.mjs';

export const FIXTURE_TIME = 1795824000;

export function writeCatalogFixture(output) {
  mkdirSync(output);
  const inputs = mkdtempSync(join(tmpdir(), 'cordiact-catalog-inputs-'));
  try {
    const root = writeKey(inputs, 'root');
    const publishers = ['alpha', 'beta'].map(id => ({ id, ...writeKey(inputs, id) }));
    const sources = publishers.map(({ id }) => ({ id, base: `https://${id}.example.test/plugins/`, packagePrefix: `${id}-` }));
    const rootConfig = {
      revision: 1, expires: FIXTURE_TIME + 180_000, trustSource: 'alpha', sources,
      grants: publishers.map(({ id, publicFile }) => ({ source: id, publisher: id, keyId: `${id}-key`, publicKey: publicFile })),
    };
    const rootOut = join(output, 'root');
    const config = writeJson(inputs, 'root.json', rootConfig);
    createCatalogRoot(config, root.privateFile, rootOut, FIXTURE_TIME);
    for (const { id, privateFile } of publishers) {
      const packageDefinition = {
        schemaVersion: 1, id: `${id}-tools`, version: '1.0.0', title: `${id} tools`, description: 'Static publisher fixture',
        host: { api: 1, minVersion: '0.1.0' }, capabilities: ['tool.publish'],
        tools: [{ name: 'summary', description: 'Give a canned summary', kind: 'static_text', text: id }], commands: [],
      };
      const artifact = writeJson(inputs, `${id}-artifact.json`, packageDefinition);
      const releaseDefinition = {
        policy: join(rootOut, 'cordiact-signed-catalog-v1.txt'), trust: join(rootOut, 'catalog/trust.cdx'), source: id,
        publisher: id, keyId: `${id}-key`, revision: 1, expires: FIXTURE_TIME + 86_400, artifacts: [artifact],
      };
      const release = writeJson(inputs, `${id}-release.json`, releaseDefinition);
      createCatalogRelease(release, privateFile, join(output, id), FIXTURE_TIME);
      if (id === 'alpha') {
        const upgrade = writeJson(inputs, 'upgrade.json', { ...packageDefinition, version: '1.0.1' });
        const index = writeJson(inputs, 'alpha-upgrade.json', { ...releaseDefinition, revision: 2, artifacts: [artifact, upgrade] });
        createCatalogRelease(index, privateFile, join(output, 'alpha-upgrade'), FIXTURE_TIME);
      }
    }
    const revoke = writeJson(inputs, 'revoke.json', { ...rootConfig, revision: 2,
      grants: rootConfig.grants.filter(grant => grant.source === 'beta') });
    createCatalogRoot(revoke, root.privateFile, join(output, 'revoked'), FIXTURE_TIME);
  } finally {
    rmSync(inputs, { recursive: true, force: true });
  }
}

function writeKey(directory, name) {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const privateFile = join(directory, `${name}.pem`);
  const publicFile = join(directory, `${name}.pub.pem`);
  writeFileSync(privateFile, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
  writeFileSync(publicFile, publicKey.export({ type: 'spki', format: 'pem' }));
  return { privateFile, publicFile };
}

function writeJson(directory, name, data) {
  const file = join(directory, name);
  writeFileSync(file, JSON.stringify(data) + '\n');
  return file;
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  writeCatalogFixture(process.argv[2]);
}
