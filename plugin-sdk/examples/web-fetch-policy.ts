import type { BeforeToolPolicy } from '@cordiact/plugin-sdk';

const guard: BeforeToolPolicy = ({ toolId }) => toolId === 'search.fetch' ? 'block' : 'continue';

export default guard;
