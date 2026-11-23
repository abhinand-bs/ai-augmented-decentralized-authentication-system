# Deployments

Public record of WalletRegistry deployments on public networks. `npm run deploy:sepolia` (in `contracts/`)
appends a row here after a successful deployment; it writes addresses, hashes and settings only, never a key or
an RPC URL.

No live deployment yet: the Sepolia deployment needs a funded deployer key and an RPC URL (see `docs/SEPOLIA.md`).
The deploy script, its guards and `scripts/check-deployment.js` were rehearsed on a local Hardhat node running
with chain ID 11155111 (see `docs/IMPLEMENTATION_LOG.md`).

| Network | Address | Deploy transaction | Block | Guardian | Delay (s) | Date | Explorer |
|---|---|---|---|---|---|---|---|
