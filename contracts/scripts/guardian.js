// What the guardian does, outside BlockAuth. On the local node the guardian is Account #1; on any other
// network it must be a separate key (for example a hardware wallet or a multisignature wallet), never the
// backend's operator key.
//
//   GUARDIAN_ACTION=status                                    npx hardhat run scripts/guardian.js --network localhost
//   GUARDIAN_ACTION=pause                                     ...
//   GUARDIAN_ACTION=cancel-unpause                            ...
//   GUARDIAN_ACTION=cancel-activation WALLET=0x...            ...
//   GUARDIAN_ACTION=revoke            WALLET=0x...            ...
//   GUARDIAN_ACTION=set-operator      NEW_OPERATOR=0x...      ...
//
// The contract address is read from deployments/<network>.json, or from CONTRACT_ADDRESS.
const hre = require('hardhat');
const fs = require('fs');
const path = require('path');

async function main() {
  const deployment = path.join(__dirname, '..', 'deployments', `${hre.network.name}.json`);
  const address = process.env.CONTRACT_ADDRESS
    || (fs.existsSync(deployment) && JSON.parse(fs.readFileSync(deployment, 'utf8')).address);
  if (!address) throw new Error('Set CONTRACT_ADDRESS or deploy first');
  const [, guardian] = await hre.ethers.getSigners();
  const registry = await hre.ethers.getContractAt('WalletRegistry', address, guardian);
  const action = process.env.GUARDIAN_ACTION || 'status';
  const wallet = process.env.WALLET;

  const run = async (tx) => {
    const receipt = await (await tx).wait();
    console.log(`${action}: done in block ${receipt.blockNumber}`);
  };
  switch (action) {
    case 'status':
      console.log({
        operator: await registry.operator(),
        guardian: await registry.guardian(),
        delaySeconds: Number(await registry.delay()),
        paused: await registry.paused(),
        unpauseReadyAt: Number(await registry.unpauseReadyAt()),
        ...(wallet ? { activationReadyAt: Number(await registry.activationReadyAt(wallet)) } : {}),
      });
      break;
    case 'pause': await run(registry.pause()); break;
    case 'cancel-unpause': await run(registry.cancelUnpause()); break;
    case 'cancel-activation': await run(registry.cancelActivation(wallet)); break;
    case 'revoke': await run(registry.revokeWallet(wallet)); break;
    case 'set-operator': await run(registry.setOperator(process.env.NEW_OPERATOR)); break;
    default: throw new Error(`Unknown GUARDIAN_ACTION ${action}`);
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
