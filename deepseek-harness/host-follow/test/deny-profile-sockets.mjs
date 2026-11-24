// Fail the synthetic CLI run on unexpected Node socket use; this is not a process sandbox.
import { Socket as DatagramSocket } from 'node:dgram'
import { Server, Socket } from 'node:net'

function offline() { throw new Error('Network and IPC sockets are disabled in the Host profile fixture') }
Server.prototype.listen = offline
Socket.prototype.connect = offline
DatagramSocket.prototype.bind = offline
DatagramSocket.prototype.connect = offline
DatagramSocket.prototype.send = offline
