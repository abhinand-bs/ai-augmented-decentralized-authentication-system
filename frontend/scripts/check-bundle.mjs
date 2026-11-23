// Fails when the production bundle contains a web3 library (the project rule: none in the production frontend).
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const dir = 'dist/assets';
const markers = [/ethers\/\d/, /\bweb3\.js\b/i, /@noble\/curves/, /secp256k1/i, /keccak256/i, /@walletconnect/i];
const hits = readdirSync(dir)
  .filter((f) => f.endsWith('.js'))
  .flatMap((f) => markers.filter((m) => m.test(readFileSync(join(dir, f), 'utf8'))).map((m) => `${f}: ${m}`));
if (hits.length) {
  console.error(`web3 code found in the production bundle:\n${hits.join('\n')}`);
  process.exit(1);
}
console.log(`No web3 library in ${dir} (checked ${markers.length} markers).`);
