import { readFileSync, realpathSync, statSync } from 'node:fs';
import { dirname, extname, isAbsolute, relative, resolve, sep } from 'node:path';
import ts from 'typescript';

const MAX_SCRIPT_BYTES = 16 * 1024;
const MAX_PACKAGE_BYTES = 32 * 1024;
const SLUG = /^[a-z][a-z0-9-]{1,23}$/;
const VERSION = /^[0-9]{1,6}\.[0-9]{1,6}\.[0-9]{1,6}$/;

export function packExtension(manifestPath) {
  const path = realpathSync(manifestPath);
  const root = dirname(path);
  const manifest = JSON.parse(readText(path, MAX_PACKAGE_BYTES));
  requireKeys(manifest, ['id', 'version', 'title', 'description', 'tools', 'commands', 'client', 'agent', 'providers', 'workflows'], 'manifest');
  const { id, version, title, description, tools = [], commands = [], client, agent, providers, workflows } = manifest;
  if (!matches(id, SLUG) || !matches(version, VERSION)) throw new Error('Invalid package id or version.');
  requireText(title, 120, 'package title');
  requireText(description, 480, 'package description');
  if (!Array.isArray(tools) || tools.length > 4) throw new Error('Declare at most four text tools.');
  if (!Array.isArray(commands) || commands.length > 4) throw new Error('Declare at most four commands.');
  if (!tools.length && !commands.length && client === undefined && agent === undefined && providers === undefined && workflows === undefined) {
    throw new Error('Declare at least one contribution.');
  }
  if (client !== undefined) {
    requireKeys(client, ['settingsPage', 'sessionActions', 'workbenchPanel', 'conversation'], 'client');
    if (!client.settingsPage && !client.sessionActions?.length && !client.workbenchPanel && client.conversation === undefined) {
      throw new Error('The client section must declare a contribution.');
    }
  }

  const names = new Set();
  const compiled = tools.map(tool => {
    requireKeys(tool, ['name', 'description', 'entry'], 'tool');
    if (!matches(tool.name, SLUG) || names.has(tool.name)) throw new Error('Invalid or duplicate tool name.');
    names.add(tool.name);
    requireText(tool.description, 480, 'tool description');
    return { name: tool.name, description: tool.description, kind: 'text_script', source: compileEntry(root, tool.entry, 'return exports.default;') };
  });
  const capabilities = [];
  if (compiled.length) capabilities.push('tool.publish', 'tool.script.execute');
  if (commands.length) capabilities.push('command.publish');
  if (client?.settingsPage) capabilities.push('ui.settings.publish');
  if (client?.sessionActions?.length) capabilities.push('ui.session-action.publish');
  if (client?.workbenchPanel) capabilities.push('ui.workbench-panel.publish');
  if (client?.conversation !== undefined) {
    validateConversation(client.conversation, names);
    capabilities.push('ui.conversation.publish');
  }
  let agentEntries;
  if (agent !== undefined) {
    requireKeys(agent, ['skills', 'context', 'beforeTool', 'subagents', 'beforeTurn'], 'agent');
    const beforeTool = agent.beforeTool === undefined ? undefined : compileToolPolicies(root, agent.beforeTool);
    const subagents = agent.subagents === undefined ? undefined : validateSubagents(agent.subagents);
    const beforeTurn = agent.beforeTurn === undefined ? undefined : compileTurnPolicy(root, agent.beforeTurn);
    if (agent.context !== undefined) {
      requireText(agent.context, 1024, 'Agent context', true);
      capabilities.push('agent.context.publish');
    }
    if (agent.skills !== undefined) {
      validateSkills(agent.skills);
      capabilities.push('agent.skill.publish');
    }
    if (beforeTool) capabilities.push('agent.tool-policy.publish');
    if (beforeTurn) capabilities.push('agent.turn-policy.publish');
    if (subagents) capabilities.push('agent.subagent-preset.publish');
    if (agent.context === undefined && agent.skills === undefined && beforeTool === undefined && subagents === undefined && beforeTurn === undefined) {
      throw new Error('The agent section must declare a contribution.');
    }
    agentEntries = { context: agent.context, skills: agent.skills, beforeTool, subagents, beforeTurn };
  }
  const providerEntries = providers === undefined ? undefined : compileProviders(root, providers);
  const workflowEntries = workflows === undefined ? undefined : compileWorkflows(root, workflows);
  if (workflowEntries) capabilities.push('workflow.publish');
  if (providerEntries?.search) capabilities.push('provider.search.publish');
  if (providerEntries?.model) capabilities.push('provider.model.publish');
  const output = JSON.stringify({
    schemaVersion: agentEntries?.beforeTurn ? 12 : client?.conversation ? 11 : agentEntries?.subagents ? 10 : workflowEntries ? 9 : agentEntries?.beforeTool ? 8 :
      providerEntries?.model ? 7 : providerEntries?.search ? 6 : agentEntries ? 5 : compiled.length ? 4 : client?.workbenchPanel ? 3 : client ? 2 : 1,
    id, version, title, description,
    host: { api: 1, minVersion: '0.1.0' }, capabilities, tools: compiled, commands, client, agent: agentEntries, providers: providerEntries, workflows: workflowEntries,
  }, null, 2) + '\n';
  if (Buffer.byteLength(output) > MAX_PACKAGE_BYTES) throw new Error('Extension exceeds the 32 KiB package limit.');
  return output;
}

function validateConversation(conversation, toolNames) {
  requireKeys(conversation, ['messages', 'toolResults'], 'conversation');
  if (conversation.messages === undefined && conversation.toolResults === undefined) throw new Error('Conversation extension cannot be empty.');
  if (conversation.messages !== undefined) {
    validateViews(conversation.messages, 'event', new Set(['user.message', 'assistant.message']));
  }
  if (conversation.toolResults !== undefined) validateViews(conversation.toolResults, 'tool', toolNames);
}

function validateViews(views, selector, allowed) {
  if (!Array.isArray(views) || views.length < 1 || views.length > allowed.size) throw new Error('Invalid number of conversation views.');
  const seen = new Set();
  for (const view of views) {
    requireKeys(view, [selector, 'title', 'lines'], 'conversation view');
    const match = view[selector];
    if (!allowed.has(match) || seen.has(match)) throw new Error('Unknown or duplicate conversation selector.');
    seen.add(match);
    requireText(view.title, 80, 'view title');
    if (!Array.isArray(view.lines) || view.lines.length < 1 || view.lines.length > 4) throw new Error('Declare one to four view lines.');
    for (const line of view.lines) requireText(line, 160, 'view line');
  }
}

function validateSubagents(presets) {
  if (!Array.isArray(presets) || presets.length < 1 || presets.length > 4) throw new Error('Declare one to four subagent presets.');
  const names = new Set();
  for (const preset of presets) {
    requireKeys(preset, ['name', 'title', 'instructions', 'tools'], 'subagent preset');
    if (!matches(preset.name, SLUG) || names.has(preset.name)) throw new Error('Invalid or duplicate subagent preset name.');
    names.add(preset.name);
    requireText(preset.title, 120, 'subagent title');
    requireText(preset.instructions, 4096, 'subagent instructions', true);
    validateExactToolIds(preset.tools, 0);
  }
  return presets;
}

function validateExactToolIds(tools, minimum) {
  if (!Array.isArray(tools) || tools.length < minimum || tools.length > 16 || new Set(tools).size !== tools.length ||
      !tools.every(id => matches(id, /^[a-z][a-z0-9._-]{1,127}$/))) {
    throw new Error('Use distinct exact tool IDs, with at most 16 per declaration.');
  }
}

function compileWorkflows(root, workflows) {
  if (!Array.isArray(workflows) || workflows.length < 1 || workflows.length > 4) throw new Error('Declare one to four workflows.');
  const names = new Set();
  return workflows.map(workflow => {
    requireKeys(workflow, ['name', 'title', 'steps'], 'workflow');
    if (!matches(workflow.name, SLUG) || names.has(workflow.name)) throw new Error('Invalid or duplicate workflow name.');
    names.add(workflow.name);
    requireText(workflow.title, 120, 'workflow title');
    if (!Array.isArray(workflow.steps) || workflow.steps.length < 1 || workflow.steps.length > 4) throw new Error('Declare one to four workflow steps.');
    return { name: workflow.name, title: workflow.title, steps: workflow.steps.map(step => {
      requireKeys(step, ['title', 'entry'], 'workflow step');
      requireText(step.title, 120, 'workflow step title');
      return { title: step.title, source: compileEntry(root, step.entry, 'return exports.default;') };
    }) };
  });
}

function compileToolPolicies(root, policies) {
  if (!Array.isArray(policies) || policies.length < 1 || policies.length > 4) throw new Error('Declare one to four before-tool policies.');
  const names = new Set();
  return policies.map(policy => {
    requireKeys(policy, ['name', 'tools', 'entry'], 'before-tool policy');
    if (!matches(policy.name, SLUG) || names.has(policy.name)) throw new Error('Invalid or duplicate tool policy name.');
    names.add(policy.name);
    validateExactToolIds(policy.tools, 1);
    return { name: policy.name, tools: policy.tools,
      source: compileEntry(root, policy.entry, 'return (input) => exports.default(JSON.parse(input));') };
  });
}

function compileTurnPolicy(root, policy) {
  requireKeys(policy, ['name', 'entry'], 'before-turn policy');
  if (!matches(policy.name, SLUG)) throw new Error('Invalid turn policy name.');
  return { name: policy.name, source: compileEntry(root, policy.entry, 'return (input) => exports.default(JSON.parse(input));') };
}

function compileProviders(root, providers) {
  requireKeys(providers, ['search', 'model'], 'providers');
  const search = providers.search === undefined ? undefined : compileSearchProviders(root, providers.search);
  const model = providers.model === undefined ? undefined : validateModelProviders(providers.model);
  if (!search && !model) throw new Error('Declare at least one provider.');
  return { search, model };
}

function compileSearchProviders(root, providers) {
  if (!Array.isArray(providers) || providers.length < 1 || providers.length > 2) throw new Error('Declare one or two search providers.');
  const names = new Set();
  return providers.map(provider => {
    requireKeys(provider, ['name', 'title', 'entry'], 'search provider');
    if (!matches(provider.name, SLUG) || names.has(provider.name)) throw new Error('Invalid or duplicate search provider name.');
    names.add(provider.name);
    requireText(provider.title, 120, 'search provider title');
    return { name: provider.name, title: provider.title, source: compileEntry(root, provider.entry,
      'return (input) => JSON.stringify(exports.default(JSON.parse(input)));') };
  });
}

function validateModelProviders(providers) {
  if (!Array.isArray(providers) || providers.length < 1 || providers.length > 2) throw new Error('Declare one or two model providers.');
  const names = new Set();
  const protocols = new Set(['OPENAI_CHAT_COMPLETIONS', 'OPENAI_RESPONSES', 'ANTHROPIC_MESSAGES', 'GEMINI_GENERATE_CONTENT']);
  for (const provider of providers) {
    requireKeys(provider, ['name', 'title', 'protocol', 'endpoint', 'models'], 'model provider');
    if (!matches(provider.name, SLUG) || names.has(provider.name)) throw new Error('Invalid or duplicate model provider name.');
    names.add(provider.name);
    requireText(provider.title, 120, 'model provider title');
    if (!protocols.has(provider.protocol)) throw new Error('Unsupported model provider protocol.');
    requireText(provider.endpoint, 1024, 'model provider endpoint');
    const endpoint = new URL(provider.endpoint);
    const authority = /^https:\/\/([^/]+)/i.exec(provider.endpoint)?.[1];
    if (!authority || authority.includes('@') || endpoint.protocol !== 'https:' || !endpoint.hostname ||
        endpoint.username || endpoint.password || /[\s?#\\]/u.test(provider.endpoint)) {
      throw new Error('Use an HTTPS model endpoint without embedded credentials, query, or fragment.');
    }
    if (!Array.isArray(provider.models) || provider.models.length < 1 || provider.models.length > 32 ||
        new Set(provider.models).size !== provider.models.length) throw new Error('Declare one to 32 distinct model IDs.');
    for (const id of provider.models) {
      requireText(id, 256, 'model ID');
      if (id !== id.trim()) throw new Error('Model IDs must not have leading or trailing spaces.');
    }
  }
  return providers;
}

function validateSkills(skills) {
  if (!Array.isArray(skills) || skills.length < 1 || skills.length > 4) throw new Error('Declare one to four Agent skills.');
  const names = new Set();
  for (const skill of skills) {
    requireKeys(skill, ['name', 'description', 'body'], 'skill');
    if (!matches(skill.name, SLUG) || names.has(skill.name)) throw new Error('Invalid or duplicate skill name.');
    names.add(skill.name);
    requireText(skill.description, 480, 'skill description');
    requireText(skill.body, 4096, 'skill body', true);
  }
}

function compileEntry(root, entry, bridge) {
  if (typeof entry !== 'string' || isAbsolute(entry) || !['.ts', '.js'].includes(extname(entry))) {
    throw new Error('A tool entry must be a relative .ts or .js path.');
  }
  const path = realpathSync(resolve(root, entry));
  const child = relative(root, path);
  if (child === '..' || child.startsWith(`..${sep}`) || isAbsolute(child)) throw new Error('A tool entry must stay in the manifest directory.');
  const text = readText(path, 64 * 1024);
  const source = ts.createSourceFile(child, text, ts.ScriptTarget.ES2022, true);
  for (const statement of source.statements) {
    const runtimeImport = ts.isImportDeclaration(statement) && !statement.importClause?.isTypeOnly;
    const importAlias = ts.isImportEqualsDeclaration(statement);
    const moduleExport = ts.isExportDeclaration(statement) && statement.moduleSpecifier;
    if (runtimeImport || importAlias || moduleExport) {
      throw new Error(`${entry} uses a runtime import. Text tool entries must be self-contained.`);
    }
  }
  const hasDefault = source.statements.some(statement =>
    ts.isExportAssignment(statement) && !statement.isExportEquals ||
    ts.canHaveModifiers(statement) && ts.getModifiers(statement)?.some(modifier => modifier.kind === ts.SyntaxKind.DefaultKeyword));
  if (!hasDefault) throw new Error(`${entry} must export a default function.`);

  const result = ts.transpileModule(text, {
    fileName: child, reportDiagnostics: true,
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, removeComments: true },
  });
  const diagnostic = result.diagnostics.find(item => item.category === ts.DiagnosticCategory.Error);
  if (diagnostic) throw new Error(`${entry}: ${ts.flattenDiagnosticMessageText(diagnostic.messageText, ' ')}`);
  const compiled = `(function () {\n'use strict';\nconst exports = {};\n${result.outputText.trim()}\n${bridge}\n})()`;
  if (Buffer.byteLength(compiled) > MAX_SCRIPT_BYTES) throw new Error(`${entry} exceeds the 16 KiB compiled script limit.`);
  return compiled;
}

function readText(path, maxBytes) {
  const stat = statSync(path);
  if (!stat.isFile() || stat.size > maxBytes) throw new Error('Input must be a regular file within its size limit.');
  return new TextDecoder('utf-8', { fatal: true }).decode(readFileSync(path));
}

function requireKeys(value, allowed, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value) ||
      Object.keys(value).some(key => !allowed.includes(key))) throw new Error(`Invalid ${label} fields.`);
}

function matches(value, expression) {
  return typeof value === 'string' && expression.test(value);
}

function requireText(value, maxBytes, label, multiline = false) {
  if (typeof value !== 'string' || !value.trim() ||
      /[\p{Cc}\p{Cf}\p{Cs}\p{Zl}\p{Zp}]/u.test(multiline ? value.replace(/[\n\t]/g, '') : value) ||
      Buffer.byteLength(value) > maxBytes) throw new Error(`Invalid ${label}.`);
}
