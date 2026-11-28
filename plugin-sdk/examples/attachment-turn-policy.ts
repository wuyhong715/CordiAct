import type { BeforeTurnPolicy } from '@cordiact/plugin-sdk';

const policy: BeforeTurnPolicy = ({ hasText, attachmentCount }) =>
  attachmentCount > 0 && !hasText ? 'block' : 'continue';

export default policy;
