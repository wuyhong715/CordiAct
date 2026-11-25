import { createHmac } from 'node:crypto'

export function makeProof(deliveryKey, { connectionId, localWorkspaceId, remoteWorkspaceId, remoteSessionId, ip, port, certificateSha256 }, nonce) {
  const key = createHmac('sha256', deliveryKey).update('cordiact-follow-proof-key/1\n').digest()
  try {
    return createHmac('sha256', key).update([
      'cordiact-follow-proof/1', connectionId, localWorkspaceId, remoteWorkspaceId, remoteSessionId,
      ip, port, certificateSha256, `${nonce}\n`,
    ].join(' '), 'ascii').digest('hex')
  } finally { key.fill(0) }
}
