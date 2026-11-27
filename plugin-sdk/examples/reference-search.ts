import type { SearchProvider } from '@cordiact/plugin-sdk';

const index = [
  { url: 'https://github.com/wuyhong715/CordiAct', title: 'CordiAct', snippet: 'Android Agent 工作台和工作区插件。' },
  { url: 'https://github.com/deepseek-ai/deepseek-harness', title: 'DeepSeek Harness', snippet: 'Cordis 插件、工作区与 Agent 组装。' },
];

const search: SearchProvider = ({ query, maxResults }) => {
  const terms = query.toLowerCase().split(/\s+/).filter(Boolean);
  return { sources: index.filter(item => terms.some(term => `${item.title} ${item.snippet}`.toLowerCase().includes(term))).slice(0, maxResults) };
};

export default search;
