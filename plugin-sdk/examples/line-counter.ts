import type { TextTool } from '@cordiact/plugin-sdk';

const countLines: TextTool = text => {
  const rows = text.length ? text.split(/\r\n|\r|\n/) : [];
  return `总行数 ${rows.length}，非空行 ${rows.filter(row => row.trim()).length}`;
};

export default countLines;
