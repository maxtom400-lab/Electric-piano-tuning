import { readFile } from 'node:fs/promises';

const { instance } = await WebAssembly.instantiate(await readFile(process.argv[2]), {});
const protocol = process.argv[3] === 'protocol';
const line = protocol ? instance.exports.run_protocol_tests() : instance.exports.run_safety_tests();
if (line !== 0) throw new Error(`Motor safety core test failed at C++ line ${line}`);
const count = protocol ? instance.exports.protocol_checks() : instance.exports.safety_checks();
console.log(`PASS: ${count} motor ${protocol ? 'protocol/lease' : 'safety core'} checks (WebAssembly host simulation, not ESP32/hardware verification)`);
