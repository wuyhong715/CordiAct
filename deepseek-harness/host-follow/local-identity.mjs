import { createPrivateKey } from 'node:crypto'
import { constants } from 'node:fs'
import { open } from 'node:fs/promises'
import { isAbsolute, normalize } from 'node:path'

const DIR_FLAGS = constants.O_RDONLY | constants.O_DIRECTORY | constants.O_NOFOLLOW
const FILE_FLAGS = constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK
const denied = () => new Error('Invalid private Follow identity directory')
const stopped = () => new Error('Follow Host stopped')
const anchored = (handle, name) => `/proc/self/fd/${handle.fd}/${name}`

function privateFile(info, uid, min, max) {
  return info.isFile() && info.uid === BigInt(uid) && info.nlink === 1n &&
    [0o400n, 0o600n].includes(info.mode & 0o7777n) && info.size >= BigInt(min) && info.size <= BigInt(max)
}

// Internal helper for an already opened, no-follow descriptor; the caller owns its close.
export async function readPrivateFollowFile(file, uid, min, max, signal) {
  const before = await file.stat({ bigint: true })
  if (!privateFile(before, uid, min, max)) throw denied()
  const size = Number(before.size)
  const first = Buffer.alloc(size + 1)
  const second = Buffer.alloc(size + 1)
  let accepted = false
  const read = async data => {
    let count = 0
    while (count < data.length) {
      if (signal?.aborted) throw stopped()
      const { bytesRead } = await file.read(data, count, data.length - count, count)
      if (!bytesRead) break
      count += bytesRead
    }
    return count
  }
  const check = async () => {
    const info = await file.stat({ bigint: true })
    if (!privateFile(info, uid, min, max) || info.dev !== before.dev || info.ino !== before.ino ||
        info.size !== before.size || info.mtimeNs !== before.mtimeNs) throw denied()
  }
  try {
    if (await read(first) !== size) throw denied()
    await check()
    // Metadata-only changes (including 0400 ↔ 0600) are harmless if all checks still pass.
    // Re-read bounded contents to detect observable edits even when timestamps are unchanged.
    if (await read(second) !== size || !first.equals(second)) throw denied()
    await check()
    if (signal?.aborted) throw stopped()
    accepted = true
    return first.subarray(0, size)
  } finally {
    second.fill(0)
    if (!accepted) first.fill(0)
  }
}

/** Linux only. The trusted caller selects the directory; neither paths nor secrets come from a peer. */
export async function withLocalFollowIdentity(directory, signal, use) {
  if (signal?.aborted) throw stopped()
  if (process.platform !== 'linux' || typeof directory !== 'string' || !isAbsolute(directory) ||
      directory === '/' || normalize(directory) !== directory || directory.endsWith('/') || directory.includes('\0')) throw denied()
  const uid = process.geteuid()
  const retained = []
  let parent
  let material
  try {
    try {
      parent = await open('/', DIR_FLAGS)
      for (const part of directory.slice(1).split('/')) {
        if (signal?.aborted) throw stopped()
        const ancestor = await parent.stat()
        // Permit a root-owned sticky transit directory such as /tmp; its selected child
        // must still pass the same ownership checks on the next iteration or at the leaf.
        if ((ancestor.uid !== 0 && ancestor.uid !== uid) ||
            ((ancestor.mode & 0o022) && !(ancestor.uid === 0 && (ancestor.mode & 0o1000)))) throw denied()
        const child = await open(anchored(parent, part), DIR_FLAGS)
        const old = parent
        parent = child
        await old.close()
      }
      const info = await parent.stat()
      if (info.uid !== uid || (info.mode & 0o7777) !== 0o700) throw denied()
      const read = async (name, min, max) => {
        if (signal?.aborted) throw stopped()
        const file = await open(anchored(parent, name), FILE_FLAGS)
        try {
          const data = await readPrivateFollowFile(file, uid, min, max, signal)
          retained.push(data)
          return data
        } finally { await file.close() }
      }
      const certificate = await read('tls-cert.pem', 1, 16_384)
      const privateKey = createPrivateKey(await read('tls-key.pem', 1, 16_384))
      const signingKey = createPrivateKey(await read('pairing-key.pem', 1, 4_096))
      const deliveryKey = await read('delivery-key.bin', 32, 32)
      material = { identity: { certificate, privateKey }, signingKey, deliveryKey }
    } catch {
      // Do not propagate filesystem paths or OpenSSL parsing details into loader logs.
      throw signal?.aborted ? stopped() : denied()
    } finally { await parent?.close() }
    if (signal?.aborted) throw stopped()
    return await use(material)
  } finally { for (const buffer of retained) buffer.fill(0) }
}
