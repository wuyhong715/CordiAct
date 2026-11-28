import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';
import { packExtension } from '../src/pack.mjs';

const example = fileURLToPath(new URL('../examples/line-counter.json', import.meta.url));

test('packs an independent TypeScript module that returns text', () => {
  const packed = JSON.parse(packExtension(example));
  assert.equal(packed.schemaVersion, 4);
  assert.deepEqual(packed.capabilities, ['tool.publish', 'tool.script.execute']);
  assert.equal(packed.tools[0].kind, 'text_script');
  const invoke = runInNewContext(packed.tools[0].source);
  assert.equal(invoke('行一\r\n\n行三'), '总行数 3，非空行 2');
});

test('rejects runtime imports and entries outside the package', context => {
  const root = mkdtempSync(join(tmpdir(), 'cordiact-sdk-'));
  context.after(() => rmSync(root, { recursive: true, force: true }));
  const manifest = JSON.parse(readFileSync(example, 'utf8'));
  const path = join(root, 'manifest.json');
  writeFileSync(path, JSON.stringify(manifest));
  const entry = join(root, 'line-counter.ts');
  writeFileSync(entry, 'import { readFileSync } from "node:fs"; export default () => readFileSync("/file", "utf8");');
  assert.throws(() => packExtension(path), /runtime import/);
  writeFileSync(entry, 'export const notDefault = () => "text";');
  assert.throws(() => packExtension(path), /export a default function/);
  rmSync(entry);
  symlinkSync(example, entry);
  assert.throws(() => packExtension(path), /stay in the manifest directory/);
});

test('packs a skill and context without executable contributions', context => {
  const root = mkdtempSync(join(tmpdir(), 'cordiact-agent-sdk-'));
  context.after(() => rmSync(root, { recursive: true, force: true }));
  const path = join(root, 'manifest.json');
  writeFileSync(path, JSON.stringify({
    id: 'review-kit', version: '1.0.0', title: 'Review kit', description: 'Helps review changes',
    agent: { skills: [{ name: 'review-change', description: 'Review', body: 'Read evidence.' }], context: 'Project terminology' },
  }));
  const packed = JSON.parse(packExtension(path));
  assert.equal(packed.schemaVersion, 5);
  assert.deepEqual(packed.capabilities, ['agent.context.publish', 'agent.skill.publish']);
  assert.deepEqual(packed.tools, []);
  assert.equal(packed.agent.skills[0].name, 'review-change');
});

test('packs a typed search adapter with a JSON bridge', () => {
  const path = fileURLToPath(new URL('../examples/reference-search.json', import.meta.url));
  const packed = JSON.parse(packExtension(path));
  assert.equal(packed.schemaVersion, 6);
  assert.deepEqual(packed.capabilities, ['provider.search.publish']);
  const invoke = runInNewContext(packed.providers.search[0].source);
  const result = JSON.parse(invoke(JSON.stringify({ query: 'CordiAct', maxResults: 1 })));
  assert.deepEqual(result.sources.map(source => source.title), ['CordiAct']);
});

test('packs an approved-host model declaration without an executable entry', context => {
  const examplePath = fileURLToPath(new URL('../examples/openai-model.json', import.meta.url));
  const packed = JSON.parse(packExtension(examplePath));
  assert.equal(packed.schemaVersion, 7);
  assert.deepEqual(packed.capabilities, ['provider.model.publish']);
  assert.deepEqual(packed.tools, []);
  const provider = packed.providers.model[0];
  assert.deepEqual(provider.models, ['gpt-5-mini']);
  assert.equal(provider.endpoint, 'https://api.openai.com/v1');
  assert.equal(provider.source, undefined);

  const root = mkdtempSync(join(tmpdir(), 'cordiact-model-sdk-'));
  context.after(() => rmSync(root, { recursive: true, force: true }));
  const draft = JSON.parse(readFileSync(examplePath, 'utf8'));
  draft.providers.model[0].endpoint += '?api_key=example';
  const path = join(root, 'invalid.json');
  writeFileSync(path, JSON.stringify(draft));
  assert.throws(() => packExtension(path), /without embedded credentials/);
});

test('packs an exact-tool veto hook without publishing a tool', () => {
  const path = fileURLToPath(new URL('../examples/web-fetch-policy.json', import.meta.url));
  const packed = JSON.parse(packExtension(path));
  assert.equal(packed.schemaVersion, 8);
  assert.deepEqual(packed.capabilities, ['agent.tool-policy.publish']);
  assert.deepEqual(packed.tools, []);
  const policy = packed.agent.beforeTool[0];
  const invoke = runInNewContext(policy.source);
  assert.deepEqual(policy.tools, ['search.fetch']);
  assert.equal(invoke(JSON.stringify({ toolId: 'search.fetch', suppliedParameters: ['url'], argumentCount: 1 })), 'block');
});

test('packs ordered workflow steps', () => {
  const path = fileURLToPath(new URL('../examples/normalize-workflow.json', import.meta.url));
  const packed = JSON.parse(packExtension(path));
  assert.equal(packed.schemaVersion, 9);
  assert.deepEqual(packed.capabilities, ['workflow.publish']);
  assert.deepEqual(packed.workflows[0].steps.map(step => step.title), ['去除首尾空格', '转为小写']);
});

test('packs a subagent preset with an explicitly empty tool list', () => {
  const path = fileURLToPath(new URL('../examples/review-subagent.json', import.meta.url));
  const packed = JSON.parse(packExtension(path));
  assert.equal(packed.schemaVersion, 10);
  assert.deepEqual(packed.capabilities, ['agent.subagent-preset.publish']);
  assert.deepEqual(packed.agent.subagents[0].tools, []);
});

test('packs message subscriptions and cards for the package own tools', () => {
  const path = fileURLToPath(new URL('../examples/conversation-views.json', import.meta.url));
  const packed = JSON.parse(packExtension(path));
  assert.equal(packed.schemaVersion, 11);
  assert.ok(packed.capabilities.includes('ui.conversation.publish'));
  assert.equal(packed.client.conversation.messages[0].event, 'assistant.message');
  assert.equal(packed.client.conversation.toolResults[0].tool, packed.tools[0].name);
});

test('packs a before-turn policy with its own capability', () => {
  const path = fileURLToPath(new URL('../examples/attachment-turn-policy.json', import.meta.url));
  const packed = JSON.parse(packExtension(path));
  assert.equal(packed.schemaVersion, 12);
  assert.deepEqual(packed.capabilities, ['agent.turn-policy.publish']);
  const invoke = runInNewContext(packed.agent.beforeTurn.source);
  assert.equal(invoke(JSON.stringify({ hasText: false, attachmentCount: 1 })), 'block');
  assert.equal(invoke(JSON.stringify({ hasText: true, attachmentCount: 1 })), 'continue');
});
