import assert from 'node:assert/strict';
import { createHash, createPublicKey, generateKeyPairSync, verify } from 'node:crypto';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { createCatalogRelease } from '../src/catalog.mjs';
import { FIXTURE_TIME, writeCatalogFixture } from '../verification/catalog-interop.mjs';

test('independent publishers produce signed indexes and exact package bytes', t => {
  const temporary = mkdtempSync(join(tmpdir(), 'cordiact-catalog-test-'));
  t.after(() => rmSync(temporary, { recursive: true, force: true }));
  const fixture = join(temporary, 'catalog');
  writeCatalogFixture(fixture);
  const policy = readFileSync(join(fixture, 'root/cordiact-signed-catalog-v1.txt'), 'ascii').split('\n');
  const frame = read(join(fixture, 'root/catalog/trust.cdx'));
  assert.equal(policy[3], sha(frame.body));
  assert.ok(verifyDocument(frame, 'CordiAct trust catalog v1', policy[1]));
  const root = JSON.parse(frame.body);
  assert.equal(root.grants.length, 2);
  for (const grant of root.grants) {
    const index = read(join(fixture, grant.source, 'catalog/indexes', `${grant.keyId}.cdx`));
    assert.ok(verifyDocument(index, 'CordiAct release index v1', grant.spki));
    assert.equal(JSON.parse(index.body).trustRevision, root.revision);
    assert.equal(JSON.parse(index.body).trustSha256, sha(frame.body));
    const release = JSON.parse(index.body).releases[0];
    assert.equal(release.sha256, sha(readFileSync(join(fixture, grant.source, 'packages', release.id, `${release.version}.json`))));
  }

  const revoked = read(join(fixture, 'revoked/catalog/trust.cdx'));
  assert.ok(verifyDocument(revoked, 'CordiAct trust catalog v1', policy[1]));
  assert.deepEqual(JSON.parse(revoked.body).grants.map(grant => grant.source), ['beta']);

  const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const privateFile = join(temporary, 'wrong.pem');
  writeFileSync(privateFile, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
  const config = { policy: join(fixture, 'root/cordiact-signed-catalog-v1.txt'), trust: join(fixture, 'root/catalog/trust.cdx'),
    source: 'alpha', publisher: 'alpha', keyId: 'alpha-key', revision: 2, expires: FIXTURE_TIME + 86_400, artifacts: [] };
  const configFile = join(temporary, 'release.json');
  const output = join(temporary, 'none');
  writeFileSync(configFile, JSON.stringify(config));
  assert.throws(() => createCatalogRelease(configFile, privateFile, output, FIXTURE_TIME), /key does not match/);
  assert.ok(!existsSync(output));
  config.trust = join(fixture, 'revoked/catalog/trust.cdx');
  writeFileSync(configFile, JSON.stringify(config));
  assert.throws(() => createCatalogRelease(configFile, privateFile, output, FIXTURE_TIME), /not authorized/);
  assert.ok(!existsSync(output));
});

function read(file) {
  const frame = readFileSync(file);
  assert.equal(frame.toString('ascii', 0, 4), 'CDX1');
  const end = 10 + frame.readUInt32BE(4);
  assert.equal(frame.length - end, frame.readUInt16BE(8));
  return { body: frame.subarray(10, end), signature: frame.subarray(end) };
}
function sha(bytes) { return createHash('sha256').update(bytes).digest('hex'); }
function verifyDocument({ body, signature }, domain, spki) {
  const key = createPublicKey({ key: Buffer.from(spki, 'base64'), format: 'der', type: 'spki' });
  return verify('sha256', Buffer.concat([Buffer.from(`${domain}\0`, 'ascii'), body]), key, signature);
}
