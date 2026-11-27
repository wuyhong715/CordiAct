import { createHash, createPrivateKey, createPublicKey, sign, verify } from 'node:crypto';
import { mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';

const MAGIC = Buffer.from('CDX1');
const TRUST_DOMAIN = Buffer.from('CordiAct trust catalog v1\0', 'ascii');
const INDEX_DOMAIN = Buffer.from('CordiAct release index v1\0', 'ascii');
const MAX_DOCUMENT = 64 * 1024;
const MAX_ARTIFACT = 32 * 1024;
const DAY = 86_400;
const ID = /^[a-z][a-z0-9-]{0,31}$/;
const PREFIX = /^[a-z][a-z0-9-]{0,19}-$/;
const PACKAGE_ID = /^[a-z][a-z0-9-]{1,23}$/;
const VERSION = /^(?:0|[1-9][0-9]{0,5})(?:\.(?:0|[1-9][0-9]{0,5})){2}$/;
const DIGEST = /^[0-9a-f]{64}$/;
const POLICY_FILE = 'cordiact-signed-catalog-v1.txt';

export function createCatalogRoot(configFile, keyFile, out, now = unixTime()) {
  const config = readConfig(configFile);
  fields(config, ['revision', 'expires', 'trustSource', 'sources', 'grants']);
  requireFresh(config.expires, now, 30);
  check(natural(config.revision), 'Invalid root revision.');
  const sources = validateSources(config.sources, config.trustSource);
  const rootKey = readPrivateKey(keyFile);
  const rootSpki = spki(createPublicKey(rootKey));
  check(Array.isArray(config.grants) && config.grants.length <= 64, 'Declare at most 64 publisher grants.');
  const grants = config.grants.map(grant => {
    fields(grant, ['source', 'publisher', 'keyId', 'publicKey']);
    const publisherKey = requireP256(createPublicKey(readBounded(resolveInput(configFile, grant.publicKey), 4096)));
    return { source: grant.source, publisher: grant.publisher, keyId: grant.keyId, spki: spki(publisherKey) };
  });
  validateGrants(grants, sources, rootSpki);
  const bytes = json({ schema: 1, revision: config.revision, expires: config.expires, grants });
  const frame = encodeFrame(bytes, rootKey, TRUST_DOMAIN);
  const policy = [
    'CordiAct catalog policy v1', rootSpki, config.revision, digest(bytes), config.trustSource,
    ...sources.map(source => `${source.id} ${source.base} ${source.packagePrefix}`),
  ].join('\n') + '\n';
  check(Buffer.byteLength(policy) <= 4096, 'APK catalog policy is too large.');
  writeNewDirectory(out, [['catalog/trust.cdx', frame], [POLICY_FILE, policy]]);
}

export function createCatalogRelease(configFile, keyFile, out, now = unixTime()) {
  const config = readConfig(configFile);
  fields(config, ['policy', 'trust', 'source', 'publisher', 'keyId', 'revision', 'expires', 'artifacts']);
  check(natural(config.revision), 'Invalid index revision.');
  requireFresh(config.expires, now, 7);
  const policy = readPolicy(resolveInput(configFile, config.policy));
  const rootFrame = decodeFrame(readBounded(resolveInput(configFile, config.trust), MAX_DOCUMENT + 82));
  check(verify('sha256', signingInput(TRUST_DOMAIN, rootFrame.body), policy.rootKey, rootFrame.signature), 'Root signature does not match APK policy.');
  const trust = readJson(rootFrame.body);
  fields(trust, ['schema', 'revision', 'expires', 'grants']);
  const trustSha256 = digest(rootFrame.body);
  check(trust.schema === 1 && natural(trust.revision) &&
    (trust.revision > policy.floor || trust.revision === policy.floor && trustSha256 === policy.floorDigest), 'Root does not match the APK trust floor.');
  requireFresh(trust.expires, now, 30);
  check(config.expires <= trust.expires, 'Index cannot outlive the root.');
  validateGrants(trust.grants, policy.sources, spki(policy.rootKey));
  const grant = trust.grants.find(item => item.source === config.source && item.publisher === config.publisher && item.keyId === config.keyId);
  check(grant, 'Publisher is not authorized by this root.');
  const source = policy.sources.find(item => item.id === config.source);
  const publisherKey = readPrivateKey(keyFile);
  check(spki(createPublicKey(publisherKey)) === grant.spki, 'Publisher private key does not match the root grant.');

  check(Array.isArray(config.artifacts) && config.artifacts.length <= 128, 'Declare at most 128 artifacts.');
  const files = [];
  const names = new Set();
  const releases = config.artifacts.map(file => {
    const data = readBounded(resolveInput(configFile, file), MAX_ARTIFACT);
    const { id, version } = readJson(data);
    check(pattern(id, PACKAGE_ID) && id.startsWith(source.packagePrefix) && pattern(version, VERSION), 'Invalid signed package ID, namespace, or exact version.');
    const path = `packages/${id}/${version}.json`;
    check(!names.has(path), 'Duplicate package and version.');
    names.add(path);
    files.push([path, data]);
    return { id, version, sha256: digest(data) };
  });
  const body = json({ schema: 1, revision: config.revision, trustRevision: trust.revision, trustSha256,
    expires: config.expires, source: config.source, publisher: config.publisher, keyId: config.keyId, releases });
  files.push([`catalog/indexes/${config.keyId}.cdx`, encodeFrame(body, publisherKey, INDEX_DOMAIN)]);
  writeNewDirectory(out, files);
}

export function validateCatalogPolicy(file) { readPolicy(file); }

function readPolicy(file) {
  const bytes = readBounded(file, 4096);
  check(bytes.every(byte => byte === 10 || byte >= 32 && byte <= 126), 'Malformed APK catalog policy.');
  const [header, encoded, revision, floorDigest, trustSource, ...rows] = bytes.toString('ascii').replace(/\n$/, '').split('\n');
  const floor = Number(revision);
  check(header === 'CordiAct catalog policy v1' && /^[1-9][0-9]{0,15}$/.test(revision) && natural(floor) && pattern(floorDigest, DIGEST), 'Malformed APK catalog policy.');
  const sources = rows.map(row => {
    const parts = row.split(' ');
    check(parts.length === 3, 'Invalid APK source row.');
    const [id, base, packagePrefix] = parts;
    return { id, base, packagePrefix };
  });
  return { rootKey: readSpki(encoded), floor, floorDigest, sources: validateSources(sources, trustSource) };
}

function validateSources(sources, trustSource) {
  check(Array.isArray(sources) && sources.length >= 1 && sources.length <= 16, 'Declare one to 16 catalog sources.');
  const ids = new Set();
  const prefixes = new Set();
  for (const source of sources) {
    fields(source, ['id', 'base', 'packagePrefix']);
    check(pattern(source.id, ID) && !ids.has(source.id), 'Invalid or duplicate catalog source ID.');
    check(pattern(source.packagePrefix, PREFIX) && ![...prefixes].some(prior =>
      source.packagePrefix.startsWith(prior) || prior.startsWith(source.packagePrefix)), 'Invalid or overlapping package namespace.');
    check(typeof source.base === 'string', 'Catalog base must be an HTTPS URL.');
    const url = new URL(source.base);
    check(url.href === source.base && url.protocol === 'https:' && !url.port && !url.username && !url.password &&
      !/[?#@%]/.test(source.base) && /^\/(?:[A-Za-z0-9_-]+\/)*$/.test(url.pathname), 'Use a normalized HTTPS catalog base with a trailing slash and no port, credentials, query, or fragment.');
    ids.add(source.id);
    prefixes.add(source.packagePrefix);
  }
  check(ids.has(trustSource), 'The trust source must refer to a configured source.');
  return sources;
}

function validateGrants(grants, sources, rootSpki) {
  check(Array.isArray(grants) && grants.length <= 64, 'Invalid root grants.');
  const keyIds = new Set();
  const keys = new Set([rootSpki]);
  for (const grant of grants) {
    fields(grant, ['source', 'publisher', 'keyId', 'spki']);
    check(sources.some(source => source.id === grant.source) && pattern(grant.publisher, ID) &&
      pattern(grant.keyId, ID) && !keyIds.has(grant.keyId), 'Invalid or duplicate publisher grant.');
    const canonical = spki(readSpki(grant.spki));
    check(canonical === grant.spki && !keys.has(canonical), 'Root and publisher keys must be distinct.');
    keyIds.add(grant.keyId);
    keys.add(canonical);
  }
}

function writeNewDirectory(out, files) {
  const output = resolve(out);
  mkdirSync(output);
  try {
    for (const [relative, data] of files) {
      const path = join(output, relative);
      mkdirSync(dirname(path), { recursive: true });
      writeFileSync(path, data, { flag: 'wx' });
    }
  } catch (error) {
    rmSync(output, { recursive: true, force: true });
    throw error;
  }
}

function encodeFrame(body, key, domain) {
  const signature = sign('sha256', signingInput(domain, body), { key, dsaEncoding: 'der' });
  check(signature.length >= 8 && signature.length <= 72, 'Unexpected ECDSA signature length.');
  const header = Buffer.alloc(10);
  MAGIC.copy(header);
  header.writeUInt32BE(body.length, 4);
  header.writeUInt16BE(signature.length, 8);
  return Buffer.concat([header, body, signature]);
}

function decodeFrame(frame) {
  check(frame.length >= 10 && frame.subarray(0, 4).equals(MAGIC), 'Invalid CDX1 frame.');
  const length = frame.readUInt32BE(4);
  const signature = frame.readUInt16BE(8);
  check(length >= 1 && length <= MAX_DOCUMENT && signature >= 8 && signature <= 72 &&
    frame.length === 10 + length + signature, 'Invalid CDX1 frame size.');
  return { body: frame.subarray(10, 10 + length), signature: frame.subarray(10 + length) };
}

function readPrivateKey(file) { return requireP256(createPrivateKey(readBounded(file, 4096))); }
function requireP256(key) {
  check(key.asymmetricKeyType === 'ec' && key.asymmetricKeyDetails.namedCurve === 'prime256v1', 'Use a P-256 key.');
  return key;
}
function spki(key) { return key.export({ type: 'spki', format: 'der' }).toString('base64'); }
function readSpki(encoded) {
  check(pattern(encoded, /^[A-Za-z0-9+/]{40,252}={0,2}$/), 'Invalid public key encoding.');
  const key = requireP256(createPublicKey({ key: Buffer.from(encoded, 'base64'), format: 'der', type: 'spki' }));
  check(spki(key) === encoded, 'Invalid public key encoding.');
  return key;
}

function requireFresh(expires, now, days) {
  check(natural(expires) && expires > now && expires - now <= days * DAY, `Expiry must be within the next ${days} days.`);
}
function resolveInput(config, file) {
  check(typeof file === 'string' && file.length > 0, 'Expected an input file path.');
  return resolve(dirname(config), file);
}
function readBounded(file, max) {
  const metadata = statSync(file);
  check(metadata.isFile() && metadata.size >= 1 && metadata.size <= max, 'Input file is empty, too large, or not regular.');
  const bytes = readFileSync(file);
  check(bytes.length >= 1 && bytes.length <= max, 'Input file size changed.');
  return bytes;
}
function json(value) {
  const raw = Buffer.from(JSON.stringify(value) + '\n');
  check(raw.length <= MAX_DOCUMENT, 'Catalog document exceeds 64 KiB.');
  return raw;
}
function readJson(bytes) {
  check(!bytes.subarray(0, 3).equals(Buffer.from([0xef, 0xbb, 0xbf])), 'JSON must use UTF-8 without a byte-order mark.');
  return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
}
function readConfig(file) { return readJson(readBounded(file, MAX_DOCUMENT)); }
function fields(object, expected) {
  check(object !== null && typeof object === 'object' && !Array.isArray(object) &&
    Object.keys(object).length === expected.length && expected.every(name => Object.hasOwn(object, name)), 'Missing or unexpected catalog fields.');
}
function signingInput(domain, body) { return Buffer.concat([domain, body]); }
function digest(bytes) { return createHash('sha256').update(bytes).digest('hex'); }
function pattern(value, expression) { return typeof value === 'string' && expression.test(value); }
function natural(value) { return Number.isSafeInteger(value) && value > 0; }
function unixTime() { return Math.floor(Date.now() / 1000); }
function check(value, message) { if (!value) throw new Error(message); }
