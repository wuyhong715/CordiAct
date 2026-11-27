/** A script tool receives one UTF-8 text argument and synchronously returns text. */
export type TextTool = (text: string) => string;

export interface AgentSkill {
  name: string;
  description: string;
  body: string;
}

export interface AgentContribution {
  skills?: readonly AgentSkill[];
  context?: string;
  beforeTool?: readonly BeforeToolDeclaration[];
  subagents?: readonly SubagentPresetDeclaration[];
}

export interface SubagentPresetDeclaration {
  name: string;
  title: string;
  instructions: string;
  tools: readonly string[];
}

export interface BeforeToolDeclaration {
  name: string;
  tools: readonly string[];
  entry: string;
}

export interface WorkflowStepDeclaration { title: string; entry: string }
export interface WorkflowDeclaration { name: string; title: string; steps: readonly WorkflowStepDeclaration[] }

export interface BeforeToolRequest {
  toolId: string;
  suppliedParameters: readonly string[];
  argumentCount: number;
}

export type BeforeToolPolicy = (request: Readonly<BeforeToolRequest>) => 'continue' | 'block';

export interface SearchRequest {
  query: string;
  maxResults: number;
}

export interface SearchResult {
  content?: string;
  sources: readonly { url: string; title?: string; snippet?: string }[];
}

export type SearchProvider = (request: Readonly<SearchRequest>) => SearchResult;

export interface ConversationViews {
  messages?: readonly { event: 'user.message' | 'assistant.message'; title: string; lines: readonly string[] }[];
  toolResults?: readonly { tool: string; title: string; lines: readonly string[] }[];
}

export interface ModelProviderDeclaration {
  name: string;
  title: string;
  protocol: 'OPENAI_CHAT_COMPLETIONS' | 'OPENAI_RESPONSES' | 'ANTHROPIC_MESSAGES' | 'GEMINI_GENERATE_CONTENT';
  endpoint: string;
  models: readonly string[];
}
