// Test-only guard: one IPv4 loopback listener, no outbound Node sockets. Not a sandbox.
import { Socket as DatagramSocket } from 'node:dgram'
import { Server, Socket } from 'node:net'
import { Server as TlsServer } from 'node:tls'

function disabled() { throw new Error('Unexpected socket operation in built TLS profile') }
const listen = Server.prototype.listen
let used = false
Server.prototype.listen = function (options, callback, ...extra) {
  if (used || !(this instanceof TlsServer) || options?.host !== '127.0.0.1' || options.port !== 0
    || Object.keys(options).sort().join(',') !== 'backlog,host,port'
    || typeof callback !== 'function' || extra.length) return disabled()
  used = true
  return listen.call(this, options, callback)
}
Socket.prototype.connect = disabled
DatagramSocket.prototype.bind = disabled
DatagramSocket.prototype.connect = disabled
DatagramSocket.prototype.send = disabled
