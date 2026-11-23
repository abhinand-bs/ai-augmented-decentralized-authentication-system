# Deploying WalletRegistry to Sepolia (optional)

The local Hardhat chain stays the default for development and tests. This guide is for running BlockAuth
against the public Sepolia test network. **Status: not done yet.** Everything below was rehearsed on a local
Hardhat node started with chain ID 11155111 (see `docs/IMPLEMENTATION_LOG.md`); the live deployment needs your
funded key and an RPC URL.

## 1. What you need

| Item | Where | Notes |
|---|---|---|
| Deployer (= operator) key | a new account you create for this, e.g. in MetaMask | Never a Hardhat test account (the script refuses them). This key goes into the backend as `BLOCKCHAIN_OPERATOR_PRIVATE_KEY`. |
| Sepolia ETH for it | a Sepolia faucet (for example the Google Cloud Web3 faucet or Alchemy's) | 0.05 ETH covers deployment and many registrations. |
| Guardian address | a second account, or better a Safe multisig on Sepolia | Kept offline. Must differ from the deployer (the script refuses otherwise). |
| RPC URL | Alchemy, Infura or another provider | Keep the URL private: it usually contains your API key. |
| Etherscan API key (optional) | etherscan.io account | For source verification. |

## 2. Deploy (PowerShell)

The Hardhat scripts read these values from the shell environment only (they are never written to a file):

    cd C:\BlockAuth\contracts
    $env:SEPOLIA_RPC_URL = '<your RPC URL>'
    $env:DEPLOYER_PRIVATE_KEY = '<deployer key, 0x...>'
    $env:GUARDIAN_ADDRESS = '<guardian address>'
    $env:REGISTRY_DELAY_SECONDS = '600'
    $env:ETHERSCAN_API_KEY = '<optional>'
    npm run deploy:sepolia

Bash:

    cd contracts
    SEPOLIA_RPC_URL=... DEPLOYER_PRIVATE_KEY=... GUARDIAN_ADDRESS=... REGISTRY_DELAY_SECONDS=600 npm run deploy:sepolia

The script waits for 2 confirmations, verifies the source on Etherscan (an "already verified" answer is fine),
writes `contracts/deployments/sepolia.json` (kept in git: addresses and hashes only) and appends a row to
`docs/DEPLOYMENTS.md`. Then close the shell or clear the variables:

    Remove-Item Env:DEPLOYER_PRIVATE_KEY, Env:SEPOLIA_RPC_URL

## 3. Check it (read-only, no key needed)

    $env:SEPOLIA_RPC_URL = '<your RPC URL>'
    npx hardhat run scripts/check-deployment.js --network sepolia

The check needs no key and sends nothing. Every line must say `OK`.

## 4. Point the backend at Sepolia (`.env.local`)

    BLOCKCHAIN_RPC_URL=<your RPC URL>
    BLOCKCHAIN_CHAIN_ID=11155111
    CONTRACT_ADDRESS=<address from deployments/sepolia.json>
    BLOCKCHAIN_OPERATOR_PRIVATE_KEY=<the deployer key>
    BLOCKCHAIN_RECEIPT_TIMEOUT=120s
    BLOCKCHAIN_CONFIRMATIONS=2
    BLOCKCHAIN_MAX_FEE_GWEI=50
    CHAIN_SYNC_SCHEDULE=0 * * * * *
    ADMIN_ACTION_DELAY=10m

`ADMIN_ACTION_DELAY` must be at least `REGISTRY_DELAY_SECONDS` (the backend refuses to start otherwise). The
backend sends EIP-1559 transactions, warns at startup and on the admin dashboard when the operator balance is
below `BLOCKCHAIN_LOW_BALANCE_ETH`, and reads the chain once a minute with the schedule above so that a free RPC
tier is not used up. For transaction links in the UI, build the frontend with
`VITE_EXPLORER_TX_URL=https://sepolia.etherscan.io/tx/`.

## 5. Rehearse without funds

    # PowerShell: a second local node that reports Sepolia's chain ID
    $env:HARDHAT_CHAIN_ID = '11155111'; npx hardhat node --port 8546
    # another shell
    $env:LOCALHOST_URL = 'http://127.0.0.1:8546'; $env:LOCALHOST_CHAIN_ID = '11155111'
    npx hardhat run scripts/deploy.js --network localhost

Then run the backend's chain tests against it with `BLOCKCHAIN_RPC_URL=http://127.0.0.1:8546` and
`BLOCKCHAIN_CHAIN_ID=11155111` (`mvnw verify -Pblockchain-it -Dit.test=WalletRegistryClientIT`).
