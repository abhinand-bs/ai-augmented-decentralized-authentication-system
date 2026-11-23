# Verification of branch `feature/risk-scoring-hardening` (2026-11-23)

Every row was run on this machine (Windows 11, Java 21.0.11, Node 24.16, Docker 29.8) unless it says otherwise.
"Local PostgreSQL" is a throwaway `postgres:16-alpine` container; "Neon test" is the Neon `test` branch
(PostgreSQL 18). Nothing was run against the main Neon database. Commands for Linux/macOS differ only in
`./mvnw` instead of `.\mvnw.cmd`.

## Results

| Area | Command | Result | Counts | Duration |
|---|---|---|---|---|
| Contracts | `cd contracts; npx hardhat test` | pass | 32 passing (21 original + 6 refusal paths + 4 contract-guardian + 1 fast-check model test) | 16 s |
| Contracts | `npx hardhat coverage` | pass | WalletRegistry 100 % statements, branches, functions, lines (was 90.43 % branches) | 20 s |
| Contracts | fast-check model test (`npm run test:model`), 60 random call sequences of up to 30 steps | pass; a planted bug (no `whenNotPaused` on `registerWallet`) is found with a 2-step counterexample | 1 property | 14 s |
| Contracts | `slither . --config-file slither.config.json --fail-medium` (Slither 0.11.6) | pass | 0 results after triage (9 reviewed, suppressed inline with reasons); a removed suppression makes it exit non-zero | ~30 s |
| Contracts | `npm audit --omit=dev` | pass | 0 vulnerabilities (39 remain in dev tooling, finding A-25) | — |
| Contracts | deploy rehearsal on a Hardhat node with chain ID 11155111 (`--network sepolia` → local node, throwaway keys) | pass | 5/5 guard cases; `check-deployment.js` 6/6 OK, keyless | — |
| Backend | `.\mvnw.cmd verify -Pblockchain-it` (local PostgreSQL, Hardhat 8545, Redis 6380) | pass | **214 unit, 219 integration**, 0 failures (includes chain, Redis and load tests) | 2 min 50 s |
| Backend | `.\mvnw.cmd verify` (local PostgreSQL; default tags) | pass | 212 unit, 192 integration | 2 min 20 s |
| Backend | `.\mvnw.cmd verify` on **Neon test** (final code; applies V4 and V5 there) | pass after one test fix | 214 unit; 198 integration with 1 failure: a timing flaw in `AlertEmailIT` (late alert mails from earlier sign-ins satisfied "at least two messages"). Test fixed to wait for the specific messages; `AlertEmailIT` then 6/6 on Neon and 2×6/6 locally. V1-V5 now on the Neon `test` branch (main branch untouched) | 19 min 38 s |
| Backend | `WalletRegistryClientIT` against chain 31337 and against a node with chain ID 11155111 | pass | 12/12 and 12/12; all backend transactions type 2 (EIP-1559) | 8 s each |
| Backend | `Eip1559TransactionTest` vs. ethers v6 | pass | signed bytes and hash identical | — |
| Backend | `LoadSmokeIT` (50 concurrent logins from 50 proxied addresses, local PostgreSQL) | pass | all 200; total 654 ms; p50 151 ms, p95 259 ms, max 268 ms; pool 5 fully used, up to 9 threads waiting; 269 → 286 threads | 24 s |
| Backend | `RedisMultiInstanceIT`, `RedisLeasesIT` | pass | 5/5, 3/3 | 37 s |
| Backend | anomaly model: scoring budget (`AnomalyModelTest`) | pass | p95 0.085 ms, max 0.704 ms per sign-in incl. explanation (target < 20 ms) | — |
| Backend | anomaly model: synthetic evaluation (`AnomalyEvaluationTest`) | pass | FPR 2.85 %; detection 100 / 23.5 / 13.25 / 100 %; AUC 1.000 / 0.936 / 0.908 / 0.995 (**synthetic data only**, see `RISK_MODEL.md`) | — |
| Frontend | `npm run lint` | pass | 0 problems | — |
| Frontend | `npm run test:coverage` | pass | 88 tests; 40.4 / 38.3 / 33.0 / 40.0 % (floor 39/37/32/39) | 4 s |
| Frontend | `npm run build`; `npm run check:bundle` | pass | JS ≈ 331 kB; no web3 library in `dist/` (a planted marker fails the check) | 1 s |
| Frontend | `npm run test:e2e` (Playwright, mocked API) | pass | 19 passed; every page at 360, 768, 1280, 1920 px without horizontal overflow; no console errors | 18 s |
| Frontend | full-stack smoke (`E2E_FULLSTACK=1`, backend jar on 18080, Hardhat 8545) | pass | register, login, dashboard, history, logout; admin revokes; user refused | 9 s |
| API | `newman run postman/BlockAuth.postman_collection.json` with signed USER and ADMIN sessions (backend jar, local PostgreSQL, Hardhat) | pass | 99 requests (101 executed with CSRF retries), **261 assertions, 0 failed** | 10 s |
| Two instances | two backend jars (18080, 18081) with the `redis` profile, one database, one Redis, Hardhat (`two_instances.mjs`) | pass | 11/11: session from A works on B; revoke on A refused on B; limits counted across A and B; 20 parallel registrations across A and B, 20 distinct hashes and nonces, all active on chain | 1.1 s for the 20 |
| Two instances | same 20 parallel registrations ×3, without Redis (control) | as expected, defects | 59/60; 9 nonce collisions retried, 1 `BLOCKCHAIN_TX_FAILED` | — |
| Two instances | same ×3 with Redis | pass | 60/60, 0 nonce retries | — |
| Docker | `docker compose config` default, `--profile mail`, `--profile redis`, redis + `docker-compose.redis.yml` | pass | 4/4 valid; default services unchanged | — |
| Docker | `docker compose build` | pass after fix | first attempt failed (`npm ci` under npm 10 rejected the frontend lock); lock regenerated; all three images build | 2 min |
| Docker | default stack up (override: local database instead of Neon, ports 13000/18545), deploy, sign-ins through nginx | pass | backend healthy; SPA and deep links 200; headers present; 3 registrations and logins; every client recorded as `172.29.53.1` (Docker Desktop gateway) | — |
| CI | `actionlint` (Docker image) on `.github/workflows/*.yml` | pass | 0 findings; every action pinned to a commit SHA | — |
| Clean clone | fresh clone of the branch, no `.env.local`: contracts `npm ci` + tests, frontend `npm ci` + lint + tests + build + bundle check, backend `mvnw verify` (local PostgreSQL) | pass after one fix | at `3ef7808`: contracts 32 passing; backend 214 unit + 198 IT, 0 failures, 1 skipped (the developer-config test, by design); frontend lint, build and bundle check OK but **coverage below the floor** (A-31). After adding tests, a second fresh clone at `3aa9cbd`: lint OK, 88 tests, floor met, build and bundle check OK | ~6 min |
| ZAP | baseline scan of the Docker stack | pass | `zap-baseline.py` (zap-stable, `--memory 4g`) on the Docker stack: 0 FAIL, 63 PASS, 5 WARN; after adding `Permissions-Policy`: 0 FAIL, 64 PASS, 4 WARN (see `security-tests/zap/README.md`). Active scan not run this time | ~2 min |

## Screenshots

Full-page screenshots of every page at 360, 768, 1280 and 1920 px: `SCREENSHOTS=1 npm run test:e2e` in
`frontend/` writes them to `frontend/e2e-screenshots/` (git-ignored). Reviewed: tables scroll inside their own
container at 360 px, long addresses and hashes wrap, focus outlines visible; one defect found and fixed (the
alert e-mail field was unstyled).

## Not verified

* A live Sepolia deployment (needs a funded key and an RPC URL; see `docs/SEPOLIA.md`).
* A live SMTP server (alert mails were tested with a mocked sender).
* The real MetaMask extension (the e2e tests use a stub that signs with real keys).
* A GitHub-hosted CI run (the branch is not pushed; workflows checked with actionlint only; `act` not installed).
* AWS KMS and a Safe multisig (not built; the contract was shown to work with a contract guardian).

## Reproduce

Windows (PowerShell) and Linux/macOS (bash) differ only where noted.

    # contracts
    cd contracts; npm ci; npx hardhat test; npx hardhat coverage; pip install slither-analyzer; slither . --config-file slither.config.json --fail-medium
    # backend: a throwaway PostgreSQL
    docker run -d --name blockauth-it-pg -e POSTGRES_PASSWORD=<pw> -e POSTGRES_DB=blockauth_it -p 127.0.0.1:55433:5432 postgres:16-alpine
    # PowerShell
    $env:TEST_DB_URL='jdbc:postgresql://127.0.0.1:55433/blockauth_it'; $env:TEST_DB_USERNAME='postgres'; $env:TEST_DB_PASSWORD='<pw>'
    cd backend; .\mvnw.cmd verify
    # bash
    TEST_DB_URL=jdbc:postgresql://127.0.0.1:55433/blockauth_it TEST_DB_USERNAME=postgres TEST_DB_PASSWORD=<pw> ./mvnw verify
    # with a Hardhat node (npx hardhat node; deploy) and a Redis (docker run -d -p 127.0.0.1:6380:6379 redis:7.4-alpine redis-server --requirepass <pw>):
    #   REDIS_PASSWORD=<pw> ... verify -Pblockchain-it        (chain, Redis and load tests too)
    # frontend
    cd frontend; npm ci; npm run lint; npm run test:coverage; npm run build; npm run check:bundle
    npx playwright install chromium; npm run test:e2e
