#!/usr/bin/env node
/**
 * Local UI-test fixture for Roomote, not a real TV or a production remote server.
 * Node built-ins only; OpenSSL creates an ephemeral RSA identity outside the repo.
 *
 *   node scripts/tv-simulator.mjs
 *
 * In an Android emulator, connect Roomote manually to 10.0.2.2. Enter the
 * pairing_code printed here. A physical USB device can use adb reverse for
 * ports 6466 and 6467, then connect to 127.0.0.1.
 *
 * Stdin commands: status, disconnect, quit. SIGINT/SIGTERM also clean up.
 * No mDNS discovery is advertised and no outbound connections are made.
 */
import tls from 'node:tls';
import { X509Certificate, createHash, timingSafeEqual } from 'node:crypto';
import { mkdtempSync, readFileSync, chmodSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { createInterface } from 'node:readline';

const MAX_FRAME = 262144;
const FEATURES = 99;
const NONCE = Buffer.from([0x12, 0x34]);
const EMPTY = Buffer.alloc(0);
const keyNames = new Map([
  [3, 'HOME'], [4, 'BACK'], [19, 'UP'], [20, 'DOWN'], [21, 'LEFT'],
  [22, 'RIGHT'], [23, 'OK'], [24, 'VOLUME_UP'], [25, 'VOLUME_DOWN'],
  [26, 'POWER'], [82, 'MENU'], [85, 'PLAY_PAUSE'], [87, 'NEXT'],
  [88, 'PREVIOUS'], [164, 'MUTE'], [178, 'INPUT'],
]);

function options(args) {
  const out = { bind: '127.0.0.1', remotePort: 6466, pairingPort: 6467 };
  for (let i = 0; i < args.length; i++) {
    const flag = args[i];
    if (flag === '--help' || flag === '-h') {
      console.log('Usage: node scripts/tv-simulator.mjs [--bind 127.0.0.1|::1] [--remote-port 6466] [--pairing-port 6467]');
      process.exit(0);
    }
    const name = { '--bind': 'bind', '--remote-port': 'remotePort', '--pairing-port': 'pairingPort' }[flag];
    if (!name || !args[i + 1]) throw new Error(`Unknown or incomplete option: ${flag}`);
    out[name] = name === 'bind' ? args[++i] : Number(args[++i]);
  }
  if (!['127.0.0.1', '::1'].includes(out.bind)) throw new Error('This fixture only binds to loopback.');
  for (const port of [out.remotePort, out.pairingPort]) {
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Ports must be integers from 1 to 65535.');
  }
  if (out.remotePort === out.pairingPort) throw new Error('Pairing and remote ports must differ.');
  return out;
}

function log(event, data = {}) {
  console.log(JSON.stringify({ time: new Date().toISOString(), event, ...data }));
}

// Independent protobuf encoder/parser: no production Roomote codec is imported.
function varint(value) {
  let n = BigInt(value);
  if (n < 0n || n > 0xffffffffffffffffn) throw new Error('Unsigned integer out of range');
  const out = [];
  do {
    const octet = Number(n & 127n);
    n >>= 7n;
    out.push(octet | (n ? 128 : 0));
  } while (n);
  return Buffer.from(out);
}
function scalar(field, value) {
  return Buffer.concat([varint(BigInt(field) << 3n), varint(value)]);
}
function message(field, bytes = EMPTY) {
  return Buffer.concat([varint((BigInt(field) << 3n) | 2n), varint(bytes.length), bytes]);
}
function concat(...parts) { return Buffer.concat(parts); }
function pairing(field, bytes = EMPTY, status = 200) {
  return concat(scalar(1, 2), scalar(2, status), message(field, bytes));
}
function readVarint(buffer, start, partial = false) {
  let value = 0n;
  for (let i = 0; i < 10; i++) {
    if (start + i >= buffer.length) {
      if (partial) return null;
      throw new Error('Truncated varint');
    }
    const octet = buffer[start + i];
    if (i === 9 && octet > 1) throw new Error('Varint exceeds uint64');
    value |= BigInt(octet & 127) << BigInt(i * 7);
    if (!(octet & 128)) return { value, next: start + i + 1 };
  }
  throw new Error('Invalid varint');
}
function decode(buffer) {
  const fields = new Map();
  let offset = 0;
  while (offset < buffer.length) {
    const tag = readVarint(buffer, offset);
    offset = tag.next;
    const field = Number(tag.value >> 3n);
    const wire = Number(tag.value & 7n);
    if (!field || field > 0x1fffffff) throw new Error('Invalid protobuf field');
    if (wire === 0) {
      const number = readVarint(buffer, offset);
      fields.set(field, { number: number.value });
      offset = number.next;
    } else if (wire === 2) {
      const size = readVarint(buffer, offset);
      offset = size.next;
      if (size.value > BigInt(buffer.length - offset)) throw new Error('Truncated protobuf bytes');
      const end = offset + Number(size.value);
      fields.set(field, { bytes: buffer.subarray(offset, end) });
      offset = end;
    } else if (wire === 1 || wire === 5) {
      offset += wire === 1 ? 8 : 4;
      if (offset > buffer.length) throw new Error('Truncated fixed-width field');
    } else {
      throw new Error(`Unsupported wire type ${wire}`);
    }
  }
  return fields;
}
function body(fields, field) {
  const bytes = fields.get(field)?.bytes;
  if (!bytes) throw new Error(`Missing message ${field}`);
  return decode(bytes);
}
function number(fields, field, fallback = 0) {
  const n = fields.get(field)?.number;
  if (n === undefined) return fallback;
  if (n > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('Scalar exceeds safe integer range');
  return Number(n);
}
function requireValue(actual, expected, name) {
  if (actual !== expected) throw new Error(`${name}: expected ${expected}, received ${actual}`);
}
function send(socket, bytes) {
  if (!socket.destroyed && socket.writable) socket.write(concat(varint(bytes.length), bytes));
}
function attachFrames(socket, handler, connection) {
  let pending = EMPTY;
  socket.on('data', chunk => {
    try {
      pending = concat(pending, chunk);
      while (pending.length) {
        const size = readVarint(pending, 0, true);
        if (!size) return;
        if (size.value > BigInt(MAX_FRAME)) throw new Error('Frame too large');
        const end = size.next + Number(size.value);
        if (pending.length < end) return;
        const fields = decode(pending.subarray(size.next, end));
        pending = pending.subarray(end);
        handler(fields);
        if (socket.destroyed || socket.writableEnded) return;
      }
    } catch (error) {
      log('protocol_error', { connection, message: error.message });
      socket.destroy();
    }
  });
}

function unsigned(bytes) {
  let first = 0;
  while (first < bytes.length - 1 && bytes[first] === 0) first++;
  return bytes.subarray(first);
}
function rsaParts(certificate) {
  const jwk = certificate.publicKey.export({ format: 'jwk' });
  if (jwk.kty !== 'RSA' || !jwk.n || !jwk.e) throw new Error('RSA client certificate required');
  return [
    unsigned(Buffer.from(jwk.n, 'base64url')),
    unsigned(Buffer.from(jwk.e, 'base64url')),
  ];
}
function peer(socket) {
  const raw = socket.getPeerCertificate(true)?.raw;
  if (!raw?.length) throw new Error('Client certificate required');
  return {
    certificate: new X509Certificate(raw),
    fingerprint: createHash('sha256').update(raw).digest('hex'),
  };
}

let config;
try {
  config = options(process.argv.slice(2));
} catch (error) {
  console.error(error.message);
  process.exit(1);
}
let directory;
let terminal;
let stopping = false;
let sequence = 0;
let keyCount = 0;
const servers = [];
const sockets = new Map();
const rawSockets = new Set();
const pairedClients = new Set();
const tv = { volume: 30, muted: false, power: true };

async function stop(reason, exitCode = 0) {
  if (stopping) return;
  stopping = true;
  log('stopping', { reason, connections: sockets.size, keys: keyCount });
  terminal?.close();
  process.stdin.pause();
  for (const [socket, session] of sockets) {
    clearInterval(session.keepalive);
    socket.destroy();
  }
  for (const socket of rawSockets) socket.destroy();
  await Promise.all(servers.map(server => new Promise(resolve => {
    if (!server.listening) return resolve();
    server.close(resolve);
  })));
  if (directory) rmSync(directory, { recursive: true, force: true });
  log('stopped', { temporaryIdentityRemoved: true });
  process.exitCode = exitCode;
}
process.once('SIGINT', () => void stop('SIGINT'));
process.once('SIGTERM', () => void stop('SIGTERM'));
process.once('uncaughtException', error => {
  log('fatal', { message: error.message });
  void stop('uncaught exception', 1);
});
process.once('unhandledRejection', error => {
  log('fatal', { message: String(error?.message ?? error) });
  void stop('unhandled rejection', 1);
});
process.once('exit', () => {
  if (directory) rmSync(directory, { recursive: true, force: true });
});

function track(socket, kind) {
  const session = { id: ++sequence, kind, keepalive: null };
  sockets.set(socket, session);
  socket.on('error', error => log('socket_error', { connection: session.id, kind, code: error.code ?? 'ERROR' }));
  socket.once('close', () => {
    clearInterval(session.keepalive);
    sockets.delete(socket);
    log('connection_closed', { connection: session.id, kind });
  });
  socket.setTimeout(kind === 'pairing' ? 120000 : 35000, () => {
    log('connection_timeout', { connection: session.id, kind });
    socket.destroy();
  });
  log('connection_open', { connection: session.id, kind });
  return session;
}

function volumeMessage() {
  return message(50, concat(scalar(6, 100), scalar(7, tv.volume), scalar(8, tv.muted ? 1 : 0)));
}
function broadcast(bytes) {
  for (const [socket, session] of sockets) {
    if (session.kind === 'remote' && session.ready) send(socket, bytes);
  }
}

try {
  directory = mkdtempSync(join(tmpdir(), 'roomote-tv-simulator-'));
  chmodSync(directory, 0o700);
  const keyPath = join(directory, 'identity-key.pem');
  const certPath = join(directory, 'identity-cert.pem');
  const result = spawnSync('openssl', [
    'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256',
    '-days', '2', '-subj', '/CN=Roomote Local Test TV',
    '-keyout', keyPath, '-out', certPath,
  ], { stdio: 'ignore', timeout: 20000 });
  if (result.status !== 0) throw new Error('OpenSSL could not create the temporary RSA test identity.');
  chmodSync(keyPath, 0o600);
  chmodSync(certPath, 0o600);
  const key = readFileSync(keyPath);
  const cert = readFileSync(certPath);
  const serverParts = rsaParts(new X509Certificate(cert));
  const tlsOptions = {
    key, cert, requestCert: true, rejectUnauthorized: false,
    minVersion: 'TLSv1.2', maxVersion: 'TLSv1.2',
    handshakeTimeout: 15000,
  };

  const pairingServer = tls.createServer(tlsOptions, socket => {
    const session = track(socket, 'pairing');
    try {
      const client = peer(socket);
      const proof = createHash('sha256').update(concat(
        ...rsaParts(client.certificate), ...serverParts, NONCE,
      )).digest();
      const code = concat(proof.subarray(0, 1), NONCE).toString('hex').toUpperCase();
      let stage = 0;
      attachFrames(socket, fields => {
        requireValue(number(fields, 1), 2, 'Pairing protocol');
        requireValue(number(fields, 2), 200, 'Pairing status');
        if (stage === 0) {
          body(fields, 10);
          send(socket, pairing(11));
          stage = 1;
        } else if (stage === 1) {
          requireValue(number(body(fields, 20), 3), 1, 'Client pairing role');
          const encoding = message(2, concat(scalar(1, 3), scalar(2, 6)));
          send(socket, pairing(20, concat(encoding, scalar(3, 2))));
          stage = 2;
        } else if (stage === 2) {
          const configuration = body(fields, 30);
          const encoding = body(configuration, 1);
          requireValue(number(encoding, 1), 3, 'Pairing encoding');
          requireValue(number(encoding, 2), 6, 'Pairing symbols');
          requireValue(number(configuration, 2), 1, 'Pairing role');
          send(socket, pairing(31));
          stage = 3;
          log('pairing_code', { connection: session.id, code, fixtureOnly: true });
        } else if (stage === 3) {
          const received = body(fields, 40).get(1)?.bytes;
          if (!received || received.length !== proof.length || !timingSafeEqual(received, proof)) {
            send(socket, pairing(41, EMPTY, 402));
            log('pairing_rejected', { connection: session.id });
            socket.end();
            return;
          }
          pairedClients.add(client.fingerprint);
          send(socket, pairing(41));
          stage = 4;
          log('paired', { connection: session.id, pairedClients: pairedClients.size });
          socket.end();
        } else {
          throw new Error('Pairing already complete');
        }
      }, session.id);
    } catch (error) {
      log('pairing_error', { connection: session.id, message: error.message });
      socket.destroy();
    }
  });

  const remoteServer = tls.createServer(tlsOptions, socket => {
    const session = track(socket, 'remote');
    try {
      const client = peer(socket);
      if (!pairedClients.has(client.fingerprint)) throw new Error('Phone must pair with this fixture first.');
      let stage = 0;
      let ping = 0;
      attachFrames(socket, fields => {
        if (stage === 0) {
          requireValue(number(body(fields, 1), 1), FEATURES, 'Remote features');
          send(socket, message(2, scalar(1, FEATURES)));
          stage = 1;
        } else if (stage === 1) {
          requireValue(number(body(fields, 2), 1), FEATURES, 'Active features');
          stage = 2;
          session.ready = true;
          // The stream may coalesce these independent frames.
          send(socket, message(40, scalar(1, tv.power ? 1 : 0)));
          send(socket, volumeMessage());
          log('remote_ready', { connection: session.id, ...tv });
          session.keepalive = setInterval(() => {
            send(socket, message(8, scalar(1, ++ping)));
          }, 5000);
        } else if (fields.has(9)) {
          requireValue(number(body(fields, 9), 1), ping, 'Ping response');
          log('pong', { connection: session.id, value: ping });
        } else if (fields.has(10)) {
          const command = body(fields, 10);
          const code = number(command, 1);
          requireValue(number(command, 2), 3, 'Key direction');
          keyCount++;
          log('key', { connection: session.id, code, name: keyNames.get(code) ?? 'OTHER', count: keyCount });
          if (code === 24) tv.volume = Math.min(100, tv.volume + 1);
          if (code === 25) tv.volume = Math.max(0, tv.volume - 1);
          if (code === 164) tv.muted = !tv.muted;
          if ([24, 25, 164].includes(code)) broadcast(volumeMessage());
          if (code === 26) {
            tv.power = !tv.power;
            broadcast(message(40, scalar(1, tv.power ? 1 : 0)));
          }
        } else {
          throw new Error('Unexpected remote message');
        }
      }, session.id);
      send(socket, message(1, concat(scalar(1, FEATURES), message(2))));
    } catch (error) {
      log('remote_rejected', { connection: session.id, message: error.message });
      socket.destroy();
    }
  });

  servers.push(pairingServer, remoteServer);
  for (const server of servers) {
    server.on('tlsClientError', error => log('tls_error', { code: error.code ?? 'TLS_ERROR' }));
    server.on('connection', socket => {
      rawSockets.add(socket);
      socket.once('close', () => rawSockets.delete(socket));
    });
  }
  await Promise.all([
    [pairingServer, config.pairingPort],
    [remoteServer, config.remotePort],
  ].map(([server, port]) => new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, config.bind, () => {
      server.removeListener('error', reject);
      if (stopping) {
        server.close(resolve);
        return;
      }
      server.on('error', error => {
        log('server_error', { code: error.code ?? 'ERROR' });
        void stop('server error', 1);
      });
      resolve();
    });
  })));
  if (!stopping) {
    log('ready', { ...config, emulatorAddress: config.bind === '127.0.0.1' ? '10.0.2.2' : null, fixtureOnly: true });
    terminal = createInterface({ input: process.stdin });
    terminal.on('line', line => {
      const command = line.trim().toLowerCase();
      if (command === 'quit' || command === 'exit') void stop('stdin quit');
      else if (command === 'disconnect') {
        for (const [socket, session] of sockets) if (session.kind === 'remote') socket.destroy();
        log('forced_disconnect');
      } else if (command === 'status') {
        log('status', { connections: sockets.size, pairedClients: pairedClients.size, keys: keyCount, ...tv });
      } else if (command) log('command_help', { commands: ['status', 'disconnect', 'quit'] });
    });
  }
} catch (error) {
  log('startup_failed', { message: error.message });
  await stop('startup failed', 1);
}
