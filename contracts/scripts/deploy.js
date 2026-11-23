const hre = require('hardhat');
const fs = require('fs');
const path = require('path');

// The deploying account becomes the operator: the key the BlockAuth backend signs with.
// GUARDIAN_ADDRESS names the separate, offline guardian key (default on the local node: Account #1).
// REGISTRY_DELAY_SECONDS is how long a reactivation or an unpause must wait (default 600 = 10 minutes);
// it must not be longer than the backend's ADMIN_ACTION_DELAY, which the backend checks at startup.
async function main() {
  const [operator, defaultGuardian] = await hre.ethers.getSigners();
  const guardian = process.env.GUARDIAN_ADDRESS || defaultGuardian.address;
  const delay = BigInt(process.env.REGISTRY_DELAY_SECONDS || '600');
  const registry = await hre.ethers.deployContract('WalletRegistry', [guardian, delay]);
  await registry.waitForDeployment();

  const address = await registry.getAddress();
  const { chainId } = await hre.ethers.provider.getNetwork();
  const record = {
    address,
    chainId: Number(chainId),
    operator: operator.address,
    guardian,
    delaySeconds: Number(delay),
    deployedAt: new Date().toISOString(),
  };

  const dir = path.join(__dirname, '..', 'deployments');
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, `${hre.network.name}.json`), JSON.stringify(record, null, 2));
  console.log(`WalletRegistry deployed to ${address} (chainId ${record.chainId}, operator ${operator.address}, `
    + `guardian ${guardian}, delay ${delay}s)`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
