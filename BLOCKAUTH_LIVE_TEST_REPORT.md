# BlockAuth live test report

Date: 2026-11-23 23:40 to 2026-11-24 03:56 IST. Branch `feature/risk-scoring-hardening` (uncommitted changes listed in section 6).

Statuses used: **PASS**, **FAIL**, **PARTIALLY PASSED**, **NOT VERIFIED**, **BLOCKED**. A feature is PASS only when the test was performed and its
output was checked (in the browser, and where it applies in the database, Redis, Mailpit or on the chain).

## 1. Summary

The real stack was run and driven through a browser. Five defects were found and fixed during the run (A-34 to A-38); each fix has a
regression test that fails without it. The run was then repeated from the start. After the fixes, every risk scenario gave exactly the
score and decision the trust policy specifies, and the database, the backend API and the admin screens agreed row for row.

The application is **not** declared issue-free:

- Two findings stay open (A-33, A-39, section 7).
- Several buttons were not exercised live (section 5).
- Sepolia is blocked: there is no configuration for it.
- GitHub CI and a real MetaMask extension were not verified.

## 2. Environment (all real, local or test-only)

| Part | What ran |
|---|---|
| Backend | Two instances of the built jar (Spring Boot 4.1.1, Java 21.0.11): A on 8080, B on 18081, profile `dev,redis` |
| Database | Neon **test** branch (PostgreSQL 18). The start script refuses to run if the test URL equals `DB_URL`; the main database was not touched |
| Redis | 7.4.11 in Docker, bound to 127.0.0.1:6380, password-protected; shared by A and B (sessions, rate limits, leases) |
| Chain | Hardhat node 127.0.0.1:8545 (chain 31337) with the deployed `WalletRegistry` |
| Mail | Mailpit (SMTP 1025, web 8025); real SMTP from the backend, nothing sent outside this machine |
| Frontend | Vite dev server 5173 (proxy to A) and 5174 (proxy to B) |
| Browser | Chromium via Playwright (`frontend/e2e/live.spec.js`, project `live`) |

Not mocked: API responses, database, Redis, chain, mail. Two things are simulated, and they are stated here so no result is overclaimed:

- **MetaMask.** `window.ethereum` is a stub. Every signature is real (ethers signs the actual challenge with a random test key), but the MetaMask extension and its prompts were not used.
- **Client networks.** Addresses from the documentation ranges (192.0.2.x, 198.51.100.x, 203.0.113.x) are sent as `X-Forwarded-For` through the local proxy, which the backend trusts only from 127.0.0.1/::1. This is how the backend sees "a new network".

All wallets are fresh random test wallets except the local administrator test wallet. No secret, key or token is printed in this
report or visible in a screenshot (see 8).

## 3. Risk scoring (highest priority)

Policy: start at 100; new device −30, new network −20, flagged wallet −35, repeated failures from the address (3 in an hour) −25,
device shared with a flagged wallet −35. Below 90 a wallet with the second step must pass it. Without one: 50–89 is allowed, below 50 is refused.

Values below are read from `login_assessments` in the test database (final run, 2026-11-24 00:32–00:35 IST; enforce phase 03:55 IST). The same rows
appear on the admin screen (screenshot 01), and the live test asserted each one through the admin API at the moment it happened.

| # | Scenario | Rules recorded | Rule score | Anomaly | Final | Decision | Expected | Status |
|---|---|---|---|---|---|---|---|---|
| S1 | First sign-in after registration | — | 100 | 0.61 | 100 | ALLOW | 100 ALLOW | PASS |
| S2 | Same device, same network | — | 100 | 0.52 | 100 | ALLOW | 100 ALLOW | PASS |
| S3 | New device, known network | NEW_DEVICE | 70 | 0.58 | 70 | ALLOW | 70 ALLOW | PASS |
| S4 | Known device, new network | NEW_NETWORK | 80 | 0.63 | 80 | ALLOW | 80 ALLOW | PASS |
| S5 | New device and new network | NEW_DEVICE, NEW_NETWORK | 50 | 0.67 | 50 | ALLOW | 50 ALLOW | PASS |
| S6 | 3 unregistered-wallet failures from one address, then the user from there on a new device | NEW_DEVICE, NEW_NETWORK, REPEATED_FAILURES | 25 | 0.70 | 25 | REFUSE | 25 REFUSE | PASS |
| S7 | Second step on, new device; real TOTP code completes it | NEW_DEVICE | 70 | 0.66 | 70 | SECOND_STEP | SECOND_STEP | PASS |
| S8 | New device; wrong code refused, backup code completes it | NEW_DEVICE | 70 | 0.66 | 70 | SECOND_STEP | SECOND_STEP | PASS |
| S9 | New device on the travel network, backup code (triggers the alert e-mail) | NEW_DEVICE | 70 | 0.66 | 70 | SECOND_STEP | SECOND_STEP | PASS |
| S10 | Second wallet, first sign-in | — | 100 | 0.61 | 100 | ALLOW | 100 ALLOW | PASS |
| S11 | That wallet flagged, new device | NEW_DEVICE, FLAGGED_WALLET | 35 | 0.56 | 35 | REFUSE | 35 REFUSE | PASS |
| S12 | Flagged wallet on its known device | FLAGGED_WALLET | 65 | 0.57 | 65 | ALLOW | 65 ALLOW | PASS (after fix A-34) |
| S13a | User signs in on the flagged wallet's browser | NEW_DEVICE | 70 | 0.68 | 70 | SECOND_STEP | SECOND_STEP | PASS |
| S13b | User on a fresh device afterwards: now linked to a flagged wallet | NEW_DEVICE, LINKED_TO_FLAGGED | 35 | 0.68 | 35 | SECOND_STEP | SECOND_STEP (has second step) | PASS |
| E0 | ENFORCE mode, first sign-in | ANOMALY_DETECTED | 100 | 0.62 | 93 | ALLOW | 100 − penalty, ALLOW | PASS |
| E1 | ENFORCE mode, new device + new network | NEW_DEVICE, NEW_NETWORK, ANOMALY_DETECTED | 50 | 0.638 | 42 | REFUSE | 50 − round(30 × 0.138 / 0.5) = 42, REFUSE (asserted exactly) | PASS |

| Check | Result | Status |
|---|---|---|
| Frontend vs backend vs database | Screenshot 01 shows the same 11 rows (time, address, rule score, anomaly, final, decision, reasons) as the SQL query and the admin API | PASS |
| Refusal shown to the user | "This sign-in was refused because it does not look like your usual sign-ins." (S6, S11); "Enter your verification code" (S7–S9, S13) | PASS |
| Refused sign-ins logged | `security_events` LOW_TRUST_LOGIN for S6 and S11 (screenshot 08) | PASS |
| Shadow mode never changes a decision | S1–S13: final = rule score in every row | PASS |
| Enforce mode lowers, never raises | E0 100→93, E1 50→42. Penalty = round(30 × (a − 0.5) / 0.5) with the test threshold 0.5. Screenshot 03 | PASS |
| Anomaly model quality on real sign-ins | The model is trained only on 2,000 synthetic sign-ins (no real data yet); see A-33 | NOT VERIFIED |

## 4. Features tested

| Area | Test | Evidence | Status |
|---|---|---|---|
| Registration | Wallet registered on the chain; transaction hash shown | Hash in "Registration details"; `isRegistered` true on chain | PASS |
| Sign-in | Challenge signed; a triple click sends one verify request | Request count = 1 | PASS |
| Logout | Logout to `/`; protected page afterwards goes to `/login` | Browser | PASS |
| Logout race with Redis | Logout while 3 page requests are in flight, 20 rounds | Before fix: 500 in 2 of 8 rounds (A-35). After: 20/20 clean, no session errors in the log | PASS (after fix) |
| Signing in again after a session was ended elsewhere | S12 path | Before fix the browser got SESSION_REVOKED on the challenge and hung (A-34) | PASS (after fix) |
| Device tracking | Sessions and devices listed with address, browser, trust period; "This device" marker | Screenshots 04, 07; `user_sessions`: 9 sessions on 7 devices for the user | PASS |
| New sign-in notice | Banner on the dashboard and "New sign-ins to check"; "This was me" clears it | Screenshot 07 | PASS |
| Second step (TOTP) | Set up with a real RFC 6238 code; 10 backup codes; TOTP, backup code and wrong-code paths | Screenshot 06; "8 of 10 backup codes left" in screenshot 04; 5 SECOND_STEP events | PASS |
| Alert e-mail | Address set, confirmed with the mailed code; new-device alert received; no links; network shown as `198.51.100.x` | Screenshot 05; 3 mails in Mailpit = 3 ALERT_EMAIL_SENT events (same second). Address stored encrypted, shown masked `l***@example.test` | PASS |
| Redis shared sessions | Session from instance A served by instance B (5174 → 18081) | Screenshot 04 (the port is not visible in a page screenshot; the test navigated to `http://localhost:5174/security` and asserted "This device") | PASS |
| Redis key namespace | Sessions under `blockauth:session:` | Before fix: `spring:session:` (A-36). After: `blockauth:session:sessions:*`, `blockauth:rl:*` | PASS (after fix) |
| Suspicious wallets | Flag with reason; unflag; LINKED_TO_FLAGGED propagates through a shared device | S11–S13; `wallet_flags` empty afterwards | PASS |
| Admin dashboard | Totals | Screenshot 02; counts reconciled with SQL at the time of capture (users 39, logins 95 = 74 + 21) | PASS |
| Security monitoring | Security events, authentication events (incl. "Alert email sent"), sign-in risk | Screenshots 01, 08 | PASS |
| Revoke on chain | Admin revokes the second wallet with a signed confirmation; transaction hash shown; the wallet is refused | Screenshot 09; `isActive` false on chain; "This wallet has been revoked by an administrator." | PASS |
| Delayed reactivation | Requested, listed as waiting, cancelled | `admin_actions` CANCELLED; `activationReadyAt` = 0 on chain | PASS |
| API keys | Created with a signature; `audit:read` reads statistics (200), not users (403); revoked → 401 | Run 2: **FAIL** (403 on its own scope, A-37). After fix: 200 / 403 / 401 | PASS (after fix) |
| Emergency stop | On; a user sign-in refused ("This is paused by an administrator.", HTTP 503); lift requested with 10-minute delay; carried out; chain unpaused | Screenshot 10; `emergency_stop.active` false; action EXECUTED; `paused` false on chain | PASS (see note) |
| Route guards and pages | `/`, `/register`, `/login`, `/dashboard`, `/history` (2 rows), `/wallet-status` (Active, read from the contract), `/admin` and `/admin/controls` as a user ("No permission", API 403), unknown path ("Page not found") | `pages` phase | PASS |
| Console and server errors | Only expected responses logged by the browser: 401 (signed-out session check), 403 (refused sign-ins), 503 (sign-in during the emergency stop). No page errors, no 5xx except that 503 | Recorded per run | PASS |

Note on the emergency stop: the lift was due at 00:45:35. The PC went into standby at 00:42:20 and woke at 03:37:12 (Windows
power events 506/507, and the backend's "clock leap" warning). The lift ran at 03:37:15, three seconds after wake-up. That shows it
runs after its time and not before. It does not show the timing under continuous operation, which the integration tests cover.

## 5. Not verified, blocked

| Item | Status | Reason |
|---|---|---|
| Sepolia deployment | BLOCKED | No Sepolia RPC URL or funded deployer key is configured (`.env.local` has no `SEPOLIA_*` entries). Nothing was deployed or claimed |
| CI/CD on GitHub | NOT VERIFIED | The branch was not pushed (you answered "Not yet"); the workflows were only linted locally earlier |
| Real MetaMask extension | NOT VERIFIED | A signing stub with real keys was used (section 2) |
| Buttons not clicked live: "This was not me", per-session "Sign out", "Sign out everywhere else", "Forget" device, "Replace backup codes", "Turn off" second step, "Stop e-mail alerts", "Reset a user's second step" | NOT VERIFIED (live) | Covered by backend integration tests and frontend tests, not by this live run |
| Redis outage while running | NOT VERIFIED (live) | Covered by integration tests (fail-closed); not repeated live |
| Docker Compose stack, mobile widths, Postman/newman | NOT VERIFIED (in this run) | Run in the previous session (`docs/IMPLEMENTATION_LOG.md` section 4); not repeated here |
| Anomaly model quality on real sign-ins | NOT VERIFIED | No real sign-in data yet |

## 6. Defects found and fixed

| ID | Defect | Fix | Proof |
|---|---|---|---|
| A-34 | A browser whose session had been ended elsewhere was refused even on the public sign-in endpoints; it could not sign in again and the login page waited forever | `ActiveUserFilter` lets `/api/auth/csrf`, `register/challenge`, `register`, `challenge`, `verify`, `second-step` and `logout` through anonymously; protected endpoints still refuse | `SessionManagementIT.aBrowserWhoseSessionWasEndedElsewhereCanSignInAgainAtOnce` fails without the fix, passes with it; live S12 |
| A-35 | With Redis, a request in flight during logout re-wrote half a session; the next request failed with 500 | `TolerantRedisSessions`: a half-written session is deleted and treated as none; saving an invalidated one is ignored | `TolerantRedisSessionsTest`; `RedisMultiInstanceIT.aHalfWrittenSessionCountsAsNoSession` fails without; race 20/20 |
| A-36 | `spring.session.redis.*` is no longer read by Spring Boot 4; sessions used the default `spring:session:` keys | Renamed to `spring.session.data.redis.*`. One-time effect: sessions under the old keys are not found (users sign in once more) | Redis key scan; the Redis IT reads the namespaced key |
| A-37 | Every API-key request stored its authentication in a new server session: a session per call by default; with Redis, a serialization failure, so API keys did not work at all. The existing test looked for the cookie name `JSESSIONID`, which this app does not use, so it could never fail | The security-context repository never saves an API-key authentication; the test checks `BLOCKAUTH_SESSION` | `ApiKeyIT.aKeyHoldsNoSession` and `RedisMultiInstanceIT.anApiKeyWorksWithRedisSessionsAndOpensNoSession` fail without (the latter with the live 403); live API-key step |
| A-38 | On a 1366-pixel screen the risk table's Decision column was out of view | Decision and Why moved next to the Final score | Screenshot 01; frontend tests 88/88, mocked UI e2e 19/19 |

Changed files: `ActiveUserFilter.java`, `TolerantRedisSessions.java` (new), `SecurityConfig.java`, `application-redis.yml`,
`SecurityMonitoring.jsx`. Tests: `SessionManagementIT`, `RedisMultiInstanceIT`, `ApiKeyIT`, `TolerantRedisSessionsTest` (new).
Live test: `frontend/e2e/live.spec.js` (new), `frontend/playwright.config.js`, `frontend/.gitignore`. Documentation: `docs/AUDIT.md`
(A-33 to A-39), `docs/IMPLEMENTATION_LOG.md`. Nothing is committed yet.

### Corrections to the live test itself (not application defects)

| Run | Stopped at | Cause | Change |
|---|---|---|---|
| 1 | S12 (hung) | A-34 (application) | Fixed in the application |
| 2 | API key | A-37 (application) | Fixed in the application; the dashboard screenshot was taken while loading, so the test now waits for the data |
| 3 | Admin sign-in refused (REPEATED_FAILURES) | The test re-registered the admin wallet every run; each attempt is a recorded failure from its address, and three in an hour correctly lowered the score | The admin is registered only when needed |
| 4 | S10 (REPEATED_FAILURES) | Runs within the same hour reused client addresses that carried earlier refusals | Each run takes its own addresses |
| 5 | — | Main phase passed (2.8 min); then `lift`, `enforce` and `pages` phases passed | The results log moved out of `test-results/`, which Playwright empties at every run (run 5's step log was lost this way; its values are taken from the database instead) |

## 7. Open findings and observations

- **A-33 (open, Low).** A young wallet scores about 0.6 on the anomaly model (`wallet_age_days`), because the synthetic training data
  has almost no new wallets. In shadow mode (the default) this changes nothing.

  In enforce mode at the default threshold 0.6, an anomaly of 0.638 (E1) would give a penalty of round(30 × 0.038 / 0.4) = 3. That turns an
  ordinary new-device + new-network sign-in (50, allowed) into 47, refused for a wallet without the second step. This is a calculation
  from the formula, not a separate test. **Keep shadow mode until the model is retrained on real sign-ins.**
- **A-39 (open, Low).** With two instances, each trains its own model on the nightly schedule (versions 2 and 3 appeared nine seconds
  apart). This is harmless with synthetic data.
- **Observation.** REPEATED_FAILURES counts every failure from an address, including "wallet already registered". Several people behind
  one shared address (office NAT) can lower each other's score. This is how the policy is written; it is recorded, not changed.
- **Observation.** The emergency stop's lift ran late because the PC was in standby (section 4 note), not because of BlockAuth.

## 8. Screenshots (`BlockAuth-Live-Test-Screenshots/`, all from the final runs, not edited)

| File | Shows |
|---|---|
| `01-risk-assessments-score-factors-decision.png` | **Mandatory**: sign-in risk list for the test user, with rule score, anomaly, final score, decision and reasons for S1–S13; model card (shadow, v1, synthetic) |
| `02-admin-dashboard-statistics.png` | Admin dashboard totals |
| `03-anomaly-model-enforce-combined-score.png` | Enforce mode: rule score vs final score (100→93, 50→42 REFUSE) |
| `04-redis-shared-session-on-instance-b.png` | The user's session served by instance B; sessions, devices, second step (8 of 10 codes left), confirmed alert e-mail (masked) |
| `05-new-device-alert-email-in-mailpit.png` | The alert e-mail as received (no links, network masked) |
| `06-second-step-totp-prompt.png` | Second-step prompt after a risky sign-in. The 6-digit code in the field was used once and has expired; the TOTP secret and backup codes are not shown |
| `07-device-tracking-and-new-sign-in-alert.png` | "New sign-ins to check" with This was me / This was not me; sessions and devices |
| `08-security-monitoring-events.png` | Security events: low-trust refusals, unregistered wallets, second-step failures, revoked wallet; also run 2's API-key failures (A-37) |
| `09-wallet-revoked-on-chain-with-transaction.png` | Revocation with its transaction hash; users list |
| `10-emergency-stop-lifted-after-delay.png` | Stop off, "Lift emergency stop" EXECUTED (due 00:45:35), no flags left, live-test API keys revoked |

## 9. Automated tests after the fixes

| Suite | Result |
|---|---|
| Backend unit (`mvnw verify`, local PostgreSQL, Redis group included) | 216 passed, 0 failed |
| Backend integration | 222 passed, 0 failed |
| Frontend unit (Vitest) | 88 passed |
| Frontend mocked UI e2e (Playwright `ui`) | 19 passed |
| ESLint (changed files) | clean |
| CodeRabbit | 3 rounds; 4 minor findings, all fixed (section 10) |

## 10. CodeRabbit review (CLI, free allowance, 3 rounds)

| Round | Scope | Returned | Action |
|---|---|---|---|
| 1 | tracked changes (10 files) | completed, 0 findings | The new files were not included, so a second round followed |
| 2 | tracked + untracked (16 files) | completed, 2 minor (both `live.spec.js`) | Both valid and fixed. (1) The verify-response waiter is now registered before the click. (2) The enforce check now asserts the exact final score and decision computed from the penalty formula, instead of only "final ≤ rule score". The enforce and pages phases were re-run and passed (E1: 0.638 → 42, REFUSE) |
| 3 | same 16 files | completed, 2 minor (both `e2e/README.md`) | Both valid and fixed: the run commands are now given for PowerShell and Bash. Not re-reviewed: this was the third round, the limit per milestone |

No critical or major findings in any round. No finding concerned the backend fixes.

## 11. Test data left on the Neon test branch

The test created users, sessions, assessments, events and two revoked test wallets on the **test** branch and the local chain.
None of it is on the main database. Clean-up done:

- flags removed;
- API keys revoked;
- the emergency stop lifted;
- the reactivation request cancelled.

No real user data was deleted or changed.

## 12. How to repeat

The services each phase needs are listed in `frontend/e2e/README.md` (section "Live run"). Then, in `frontend/` (Bash syntax;
PowerShell: `$env:E2E_LIVE='1'; $env:LIVE_PHASE='lift'; npx playwright test`):

```
E2E_LIVE=1 npx playwright test                       # main phase
E2E_LIVE=1 LIVE_PHASE=lift npx playwright test       # after the 10-minute delay
E2E_LIVE=1 LIVE_PHASE=pages npx playwright test
E2E_LIVE=1 LIVE_PHASE=enforce npx playwright test    # backend A restarted with ANOMALY_MODE=enforce ANOMALY_THRESHOLD=0.5
```
