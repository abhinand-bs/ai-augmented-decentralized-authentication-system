const hre = require('hardhat');
const fs = require('fs');
const path = require('path');

// The deploying account becomes the operator: the key the BlockAuth backend signs with.
// GUARDIAN_ADDRESS names the separate, offline guardian key. On the local node it defaults to Account #1; on any
// other network it is required and must differ from the deployer.
// REGISTRY_DELAY_SECONDS is how long a reactivation or an unpause must wait (default 600 = 10 minutes); it must
// not be longer than the backend's ADMIN_ACTION_DELAY, which the backend checks at startup.
// CONFIRMATIONS (default 1 locally, 2 elsewhere) blocks to wait for before the deployment counts.

const HARDHAT_MNEMONIC = 'test test test test test test test test test test test junk';
const LOCAL_NETWORKS = new Set(['hardhat', 'localhost']);

/** The public Hardhat development accounts: anyone can sign with them, so they must never deploy for real. */
function publicTestAccounts() {
  const { HDNodeWallet, Mnemonic } = hre.ethers;
  const root = HDNodeWallet.fromMnemonic(Mnemonic.fromPhrase(HARDHAT_MNEMONIC), "m/44'/60'/0'/0");
  return new Set(Array.from({ length: 20 }, (_, i) => root.deriveChild(i).address.toLowerCase()));
}

async function main() {
  const local = LOCAL_NETWORKS.has(hre.network.name);
  const [operator, defaultGuardian] = await hre.ethers.getSigners();
  const guardian = process.env.GUARDIAN_ADDRESS || (local ? defaultGuardian.address : null);
  if (!guardian || !hre.ethers.isAddress(guardian)) {
    throw new Error('Set GUARDIAN_ADDRESS to the guardian key (a different, offline account or a multisig)');
  }
  if (!local && publicTestAccounts().has(operator.address.toLowerCase())) {
    throw new Error('Refusing to deploy with a public Hardhat test account on a non-local network');
  }
  if (!local && publicTestAccounts().has(guardian.toLowerCase())) {
    throw new Error('Refusing to use a public Hardhat test account as guardian on a non-local network');
  }
  if (guardian.toLowerCase() === operator.address.toLowerCase()) {
    throw new Error('The guardian must differ from the deployer (the operator)');
  }
  const delay = BigInt(process.env.REGISTRY_DELAY_SECONDS || '600');
  const confirmations = Number(process.env.CONFIRMATIONS || (local ? 1 : 2));

  const registry = await hre.ethers.deployContract('WalletRegistry', [guardian, delay]);
  const deployment = registry.deploymentTransaction();
  const receipt = await deployment.wait(confirmations);
  const address = await registry.getAddress();
  const { chainId } = await hre.ethers.provider.getNetwork();
  const record = {
    network: hre.network.name,
    address,
    chainId: Number(chainId),
    operator: operator.address,
    guardian,
    delaySeconds: Number(delay),
    transactionHash: deployment.hash,
    blockNumber: receipt.blockNumber,
    constructorArgs: [guardian, delay.toString()],
    deployedAt: new Date().toISOString(),
  };

  const dir = path.join(__dirname, '..', 'deployments');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, `${hre.network.name}.json`), `${JSON.stringify(record, null, 2)}\n`);
  console.log(`WalletRegistry deployed to ${address} (chainId ${record.chainId}, block ${record.blockNumber}, `
    + `operator ${operator.address}, guardian ${guardian}, delay ${delay}s)`);

  if (hre.network.name === 'sepolia') {
    await verify(address, [guardian, delay]);
    appendDeploymentDoc(record, `https://sepolia.etherscan.io/address/${address}`);
  }
}

/** Source verification on Etherscan; a contract that is already verified is fine. */
async function verify(address, constructorArguments) {
  if (!process.env.ETHERSCAN_API_KEY) {
    console.log('ETHERSCAN_API_KEY not set: skipping source verification');
    return;
  }
  try {
    await hre.run('verify:verify', { address, constructorArguments });
  } catch (e) {
    if (/already verified/i.test(String(e.message))) console.log('Already verified');
    else throw e;
  }
}

/** A public-safe entry in docs/DEPLOYMENTS.md: addresses, hashes and settings only, never a key or an RPC URL. */
function appendDeploymentDoc(record, link) {
  const file = path.join(__dirname, '..', '..', 'docs', 'DEPLOYMENTS.md');
  const header = '# Deployments\n\nPublic record of WalletRegistry deployments on public networks.\n\n'
    + '| Network | Address | Deploy transaction | Block | Guardian | Delay (s) | Date | Explorer |\n'
    + '|---|---|---|---|---|---|---|---|\n';
  if (!fs.existsSync(file)) fs.writeFileSync(file, header);
  fs.appendFileSync(file, `| ${record.network} | \`${record.address}\` | \`${record.transactionHash}\` | `
    + `${record.blockNumber} | \`${record.guardian}\` | ${record.delaySeconds} | ${record.deployedAt.slice(0, 10)} | `
    + `[Etherscan](${link}) |\n`);
}

main().catch((error) => {
  console.error(error.message || error);
  process.exitCode = 1;
});
