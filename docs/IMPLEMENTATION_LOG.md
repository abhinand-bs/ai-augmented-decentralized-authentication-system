# Implementation log: branch `feature/risk-scoring-hardening`

Every command that was run, with a one-line result. Newest section last. If a session is cut off, resume
from the "Open items / next steps" list at the end.

## 0. Safety and branches (2026-11-23)

| Command | Result |
|---|---|
| `git status --short` | 82 changed or untracked paths on `main` (two-key contract, ChainRequestGuard, ChainStatusReconciler, migration V3, new ITs, attack-check docs) |
| `git checkout -b safety/pre-hardening-2026-11-23; git add -A; git commit` | All local work committed on the safety branch (88 files). No `.env.local`, keys or `*.local.md` staged (checked with `git status` and a grep of the untracked docs for key/URL patterns) |
| `git checkout -b feature/risk-scoring-hardening` | Work branch created from the safety commit. Nothing pushed |

Decision: migration numbering. The prompt names V3 (risk) and V4 (mail), but the repository already has
`V3__unexpected_chain_request.sql` (it was uncommitted on `main`). New migrations therefore start at **V4**.
V1, V2 and V3 are never edited.

Decision: Redis port. Port 6379 on this machine is held by another project's container (`ioms-redis`), so
BlockAuth's optional Redis is published on `127.0.0.1:6380`.

## 1. Phase 0 baseline (before any code change)

Test database check (host and database name only, no credentials printed): `DB_URL` and `TEST_DB_URL`
point at different hosts (Neon `production` and `test` branch endpoints). Integration tests ran only
against `TEST_DB_URL`.

| Command | Result | Duration |
|---|---|---|
| `cd contracts; npm ci; npx hardhat test` | 21 passing, 0 failing | 63 s (53 s install) |
| `cd contracts; npm audit` | 39 vulnerabilities (15 low, 12 moderate, 12 high), all in dev dependencies | — |
| `cd contracts; npm audit --omit=dev` | 0 vulnerabilities | — |
| `cd frontend; npm ci; npm run build` | Build OK, 61 modules, JS 327.28 kB (99.79 kB gzip) | 11 s |
| `cd frontend; npm audit` | 0 vulnerabilities | — |
| `cd backend; ./mvnw -B -q test` | 192 unit tests, 0 failures, 0 errors | 33 s |
| `mvnw -B verify` in the main checkout (first attempt) | **Invalid, stopped.** A unit-test run in the same checkout recompiled `target/classes` while failsafe was still running, so later ITs would have mixed old and new code. Stopped with TaskStop; the orphaned Maven JVM (PID 40864, this session's) stopped with `Stop-Process`. Neon `test` branch checked afterwards: still V1-V3 only (`flyway_schema_history`) |
| `git worktree add <scratchpad>/baseline safety/pre-hardening-2026-11-23`, then `mvnw.cmd -B verify` there with only `TEST_DB_*` exported | Unit: **192/192**. Integration (Neon `test`): **180 run, 179 passed, 1 failed**: `ConfiguredAdminWalletsIT` reads the developer's own `.env.local`, which a fresh checkout (worktree, CI) does not have, so its context fails to bind. Environment-specific, not a product defect; logged as audit finding A-14. 19 min 13 s |

Lesson recorded: never run Maven goals in a checkout whose ITs are still running. Integration runs use their
own worktree or wait.

## 2. Work done so far (2026-11-23), newest last

| Area | Commands | Result |
|---|---|---|
| Contracts | `npx hardhat coverage` (before) | 100 % statements/lines/functions, **90.43 % branches** |
| Contracts | new tests `WalletRegistry.branches.test.js`, `ContractGuardian.test.js`, `WalletRegistry.model.test.js` (fast-check), `mocks/MultisigGuardian.sol` | `npx hardhat test`: **32 passing** (was 21) |
| Contracts | mutation check: removed `whenNotPaused` from `registerWallet`, ran the model test, restored | Model test **failed** with counterexample `[pause, registerWallet]`, as it must; contract restored (`git diff` empty) |
| Contracts | `npx hardhat coverage` (after; `.solcover.js` skips mocks) | WalletRegistry **100 / 100 / 100 / 100** |
| Contracts | `pip install --user slither-analyzer solc-select` (0.11.6), `slither . --config-file slither.config.json` | 9 findings (3 incorrect-equality Medium, 6 timestamp Low), all on enum/epoch comparisons or the intended delay checks; suppressed inline with written reasons; re-run: **0 results** |
| Frontend | `npm i -D eslint@9 ...` (ESLint 10 is not supported by eslint-plugin-jsx-a11y 6.10) | 0 vulnerabilities |
| Frontend | `npx eslint .` (before fixes) | 4 errors: setState in effects (useApi, Layout, AuthContext), ref written during render (AuthContext) |
| Frontend | fixes (see AUDIT.md F-*), `npx eslint .`, `npm run build` | lint clean; build OK (JS 330.99 kB) |
| Frontend | `npx vitest run --coverage` | **80 tests passing**; coverage 36.9 % statements, 33.7 % branches, 29.6 % functions, 36.3 % lines; floor set to 35/32/28/35 |
| Backend | anomaly model unit tests: `mvnw test -Dtest=IsolationForestTest,AnomalyModelTest,AnomalyEvaluationTest,TrustPolicyTest,TrustServiceTest,LoginServiceTest` | green after fixing two test mistakes (2026-11-23 is a Monday; first explanation case was a single-feature outlier, see RISK_MODEL.md) |
| Backend | synthetic evaluation (AnomalyEvaluationTest, n=2000 each, threshold 0.6) | FPR 2.85 %; detection: odd-hour new device+network 100 % (AUC 1.000), failure burst 23.5 % (AUC 0.936), many IPs 13.25 % (AUC 0.908), shared-device link 100 % (AUC 0.995). **Synthetic data only.** |
| Backend | scoring budget (AnomalyModelTest) | p95 **0.085 ms**, max 0.704 ms per sign-in including the leave-one-out explanation (n=2000) |

Commits on `feature/risk-scoring-hardening`: `cab339c` (contracts), `af82f5a` (frontend).
| Backend | `mvnw verify` against a local `postgres:16-alpine` container (`blockauth-it-pg`, 127.0.0.1:55433, throwaway password; never Neon) | 212 unit + 182 IT (later 192 IT) **green**, 2 min 20 s |
| Backend | `SinglePoolConnectionIT` with the **old** `SessionManagement` (reverted temporarily) | **2 of 2 failed**, "Failed to record" logged twice: finding A-01 confirmed; fix restored |
| Backend | `Eip1559TransactionTest` vs. a vector produced by ethers v6 (`Wallet.signTransaction`) | byte-for-byte equal signed transaction and hash |
| Backend | `WalletRegistryClientIT` (12) on Hardhat chain 31337 and on a second node with chain ID 11155111 | 12/12 and 12/12; all 24 backend transactions on chain are type `0x2` |
| Backend | `WalletRegistryClientLockTest` | second write refused with BLOCKCHAIN_BUSY after the 300 ms lock wait |
| Contracts | `deploy.js` rehearsal on the 11155111 node via `--network sepolia` with throwaway keys (`guard-rehearsal.js`) | 5/5: public Hardhat key refused, missing guardian refused, guardian = deployer refused, public guardian refused, fresh keys deployed; `check-deployment.js` 6/6 OK; rehearsal records deleted afterwards |
| Frontend | `npx playwright test` (UI layer) | **19 passed** (every page at 360/768/1280/1920 px with no horizontal overflow) |
| Frontend | `E2E_FULLSTACK=1 BACKEND_URL=http://localhost:18080 npx playwright test` (backend jar on 18080, local DB `blockauth_e2e`, Hardhat 8545) | **1 passed** when runs are a minute apart; runs closer together are refused by the real per-IP limits (10 challenges/min) and, after three failures in an hour, by the trust score: both correct behaviour, documented in `frontend/e2e/README.md` |
| Frontend | smoke test found a real defect: logout sometimes landed on `/login` (React Router 7 navigation is a transition; clearing the user urgently let the old page's guard redirect first) | fixed (one transition); smoke passes |
| Frontend | `npm run check:bundle` after `npm run build`; then a planted `ethers/6.17.0` marker | clean bundle passes; planted marker fails the check |
| Backend | `LoadSmokeIT` (50 concurrent logins from 50 proxied addresses, local DB) | all 200; 654 ms total; p50 151 ms, p95 259 ms, max 268 ms; pool 5, peak active 5, peak waiting 9; threads 269 before, JVM peak 286 |
| Backend | `RedisMultiInstanceIT` (2 app contexts, Redis 7.4 container on 127.0.0.1:6380 with password) | **5/5** after fixing two test mistakes (TOTP code reused within its step; health probe sent a session cookie) |
| Backend | `RedisLeasesIT` | 3/3 |
| Docker | `docker compose config` default, `--profile mail`, `--profile redis`, redis + `docker-compose.redis.yml` | all valid; default services unchanged (hardhat, backend, frontend) |
