require('@nomicfoundation/hardhat-toolbox');

// Everything network-specific comes from the environment; nothing secret is written here.
//   HARDHAT_CHAIN_ID      chain ID of `npx hardhat node` (default 31337; 11155111 rehearses Sepolia locally)
//   LOCALHOST_URL         RPC of the local node used by --network localhost (default http://127.0.0.1:8545)
//   SEPOLIA_RPC_URL, DEPLOYER_PRIVATE_KEY   enable --network sepolia (skipped when either is unset)
//   ETHERSCAN_API_KEY     source verification on Etherscan
const env = process.env;
const localChainId = Number(env.HARDHAT_CHAIN_ID || 31337);

const networks = {
  hardhat: { chainId: localChainId },
  localhost: { url: env.LOCALHOST_URL || 'http://127.0.0.1:8545', chainId: Number(env.LOCALHOST_CHAIN_ID || localChainId) },
};
if (env.SEPOLIA_RPC_URL && env.DEPLOYER_PRIVATE_KEY) {
  networks.sepolia = { url: env.SEPOLIA_RPC_URL, chainId: 11155111, accounts: [env.DEPLOYER_PRIVATE_KEY] };
}

/** @type import('hardhat/config').HardhatUserConfig */
module.exports = {
  solidity: {
    version: '0.8.24',
    // The optimizer is on for real deployments (smaller, cheaper bytecode); solidity-coverage turns it off itself.
    settings: { optimizer: { enabled: true, runs: 200 } },
  },
  networks,
  etherscan: { apiKey: env.ETHERSCAN_API_KEY || '' },
  sourcify: { enabled: false },
};
