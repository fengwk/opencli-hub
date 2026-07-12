#!/usr/bin/env node
//
// Derive the Chrome extension ID and update metadata for a CRX3 produced by
// google-chrome-stable --pack-extension. CRX signing itself is deliberately
// delegated to Chrome instead of reimplementing the CRX3 protobuf/signature format.

import * as crypto from 'node:crypto'
import * as fs from 'node:fs'
import * as path from 'node:path'

function fail(message) {
    console.error(`[build-extension-crx] ${message}`)
    process.exit(1)
}

if (process.argv.length !== 8) {
    fail('usage: build-extension-crx.mjs <unpacked-dir> <private-key> <crx> <updates.xml> <build-info.json> <version>')
}

const unpackedDir = process.argv[2]
const privateKeyPath = process.argv[3]
const crxPath = process.argv[4]
const updatesPath = process.argv[5]
const infoPath = process.argv[6]
const expectedVersion = process.argv[7]
const manifestPath = path.join(unpackedDir, 'manifest.json')

for (const required of [manifestPath, privateKeyPath, crxPath]) {
    if (!fs.existsSync(required)) {
        fail(`required file not found: ${required}`)
    }
}

const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
if (manifest.version !== expectedVersion) {
    fail(`extension version mismatch: manifest=${manifest.version} expected=${expectedVersion}`)
}

const crx = fs.readFileSync(crxPath)
if (crx.length < 12 || crx.subarray(0, 4).toString('ascii') !== 'Cr24' || crx.readUInt32LE(4) !== 3) {
    fail(`Chrome pack-extension did not produce a CRX3 file: ${crxPath}`)
}

const privateKey = crypto.createPrivateKey(fs.readFileSync(privateKeyPath))
const publicKeyDer = crypto.createPublicKey(privateKey).export({ type: 'spki', format: 'der' })
const idBytes = crypto.createHash('sha256').update(publicKeyDer).digest().subarray(0, 16)
const extensionId = [...idBytes]
    .flatMap((byte) => [byte >>> 4, byte & 0x0f])
    .map((nibble) => String.fromCharCode('a'.charCodeAt(0) + nibble))
    .join('')

if (!/^[a-p]{32}$/.test(extensionId)) {
    fail(`derived invalid Chrome extension ID: ${extensionId}`)
}

const updatesXml = `<?xml version="1.0" encoding="UTF-8"?>
<gupdate xmlns="http://www.google.com/update2/response" protocol="2.0">
  <app appid="${extensionId}">
    <updatecheck codebase="__UPDATE_BASE__/${path.basename(crxPath)}" version="${expectedVersion}" />
  </app>
</gupdate>
`

fs.mkdirSync(path.dirname(updatesPath), { recursive: true })
fs.writeFileSync(updatesPath, updatesXml)

const info = {
    extensionId,
    extensionVersion: expectedVersion,
    extensionName: manifest.name,
    crxSha256: crypto.createHash('sha256').update(crx).digest('hex'),
    publicKeySha256: crypto.createHash('sha256').update(publicKeyDer).digest('hex'),
    crxSize: crx.length,
    packedBy: 'google-chrome-stable --pack-extension',
}
fs.writeFileSync(infoPath, `${JSON.stringify(info, null, 2)}\n`)

console.log(`[build-extension-crx] extension ID: ${extensionId}`)
console.log(`[build-extension-crx] CRX3 sha256: ${info.crxSha256}`)
