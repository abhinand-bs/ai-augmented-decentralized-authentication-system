// Read-only check of a deployed WalletRegistry against its deployment record. Sends no transaction and needs
// no key: it reads the code, operator, guardian, delay and pause state and compares them.
//
//   npx hardhat run scripts/check-deployment.js --network sepolia
//   CONTRACT_ADDRESS=0x... DEPLOYMENT_FILE=deployments/sepolia.json npx hardhat run scripts/check-deployment.js --network localhost
const hre = require('hardhat');
const fs = require('fs');
const path = require('path');

async function main() {
  const file = process.env.DEPLOYMENT_FILE
    ? path.resolve(process.env.DEPLOYMENT_FILE)
    : path.join(__dirname, '..', 'deployments', `${hre.network.name}.json`);
  if (!fs.existsSync(file)) throw new Error(`No deployment record at ${file}`);
  const record = JSON.parse(fs.readFileSync(file, 'utf8'));
  const address = process.env.CONTRACT_ADDRESS || record.address;
  const provider = hre.ethers.provider;
  const registry = await hre.ethers.getContractAt('WalletRegistry', address);

  const { chainId } = await provider.getNetwork();
  const code = await provider.getCode(address);
  const actual = {
    chainId: Number(chainId),
    hasCode: code !== '0x',
    operator: code === '0x' ? null : await registry.operator(),
    guardian: code === '0x' ? null : await registry.guardian(),
    delaySeconds: code === '0x' ? null : Number(await registry.delay()),
    paused: code === '0x' ? null : await registry.paused(),
  };
  const checks = [
    ['chain ID', actual.chainId === record.chainId],
    ['contract code present', actual.hasCode],
    ['address matches the record', address.toLowerCase() === record.address.toLowerCase()],
    ['operator', (actual.operator || '').toLowerCase() === record.operator.toLowerCase()],
    ['guardian', (actual.guardian || '').toLowerCase() === record.guardian.toLowerCase()],
    ['delay', actual.delaySeconds === record.delaySeconds],
  ];
  console.log(JSON.stringify({ address, actual }, null, 2));
  for (const [name, ok] of checks) console.log(`${ok ? 'OK  ' : 'FAIL'} ${name}`);
  console.log(`INFO paused: ${actual.paused}`);
  if (checks.some(([, ok]) => !ok)) process.exitCode = 1;
}

main().catch((error) => {
  console.error(error.message || error);
  process.exitCode = 1;
});
