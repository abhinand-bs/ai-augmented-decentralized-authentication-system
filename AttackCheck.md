# BlockAuth — Attack Check

This document records how BlockAuth stands up to every attack in the attack test plan (`attacks.md`). It
says what was checked, what was found, what was fixed, and what still carries risk. Every result here
comes from a test that was actually run on 21 October 2026, or is clearly marked as a code review or as
not tested.

---

## 1. Purpose

BlockAuth lets people sign in with a crypto wallet instead of a password. A login is safe only if the
server refuses every attempt that is not really from the wallet's owner. This check:

- went through each attack in the plan, from basic to hard;
- found the code that is supposed to stop it;
- ran automated tests that try the attack and look at what the server answers;
- fixed the weaknesses that were confirmed, and added tests that prove the fixes;
- writes down honestly what is still not protected.

A second round on 22 October 2026 fixed the three most serious remaining risks: the operator key
(4.6), the shared address behind the Docker proxy (4.7) and what a stolen admin session can still do
(4.8). The tables below show the state after both rounds.

Everything was run only against the local test setup: the backend on this computer, a separate test
database (Neon `test` branch, which the tests empty before each test) and a local Hardhat blockchain. No
public website, real user or live contract was touched.

---

## 2. Project security overview

These protections exist in the code and were tested (details in sections 3 and 6):

| Protection | What it does, in simple words |
|---|---|
| One-time sign-in messages | The server gives out a random message that works once and expires after 120 seconds. Using it a second time, late, or for another wallet fails. |
| Signature check | The server works out which wallet signed the message and compares it with the wallet that was claimed. Odd or "twisted" signatures (high-s) are refused. |
| Blockchain check | A wallet must be registered and active in the `WalletRegistry` contract to sign in. |
| Server sessions | After sign-in the browser gets a session cookie that scripts cannot read. The server checks the session record on every request, so an ended session stops at once. The session ID changes at login. |
| Background chain check (new) | Every 15 seconds the server re-reads the blockchain for every signed-in wallet and signs out wallets that are no longer active. |
| Roles | Normal users cannot reach admin pages. Attempts are refused with 403 and written to a log. |
| CSRF protection | Every POST needs a secret token from a cookie, and a POST from another website's page is refused. |
| Rate limits | Too many requests from one address get "429 Too Many Requests". Ten failed sign-ins in 15 minutes block that address for 15 minutes. |
| Input checks | Bad addresses, bad signatures, unknown JSON fields and bodies over 16 KB are refused with 400 or 413. Database queries never paste user text into SQL. |
| Second step | Optional codes from an authenticator app, plus backup codes. Each code works once; five wrong tries end the attempt. |
| Fresh signatures | Dangerous actions (revoke a wallet, create or revoke an API key, cancel a waiting action, emergency stop, …) need a new wallet signature that names the exact action, its details and the session. |
| Delays | Actions that give access back (reactivate a wallet, reset a second step, lift the emergency stop) wait 10 minutes, and any admin can cancel them. Reactivations and unpauses also wait on the blockchain, where the contract enforces it. |
| Two-key contract (new) | The server's operator key cannot give access back at once: the contract makes it wait. A separate guardian key, kept offline, can cancel waiting requests, pause the contract and replace the operator. |
| Read-only API keys | Keys for scripts can only read what their scope allows, expire, and stop working when revoked. |
| Fail closed | If the blockchain, the database or the risk check cannot answer, the sign-in is refused, never let through. |

---

## 3. Attack-by-attack results

Status labels:

- **PASS:** the protection was tested and worked.
- **FAIL:** the attack succeeded, or the protection does not exist.
- **PARTIAL:** some protection exists, but a weakness remains.
- **NOT TESTED:** the test could not be run.
- **OUT OF SCOPE:** the server cannot fully prevent it.

"Before fix" describes the code as it was at the start of this check. Test names refer to files in
`backend/src/test/java/com/blockauth/` (`*IT` = integration test against the running app, `*Test` = unit
test) and `contracts/test/`.

### 3.1 Basic attacks

| Attack | What It Tries to Do | Before Fix | Fix Applied | Final Result | Evidence |
|---|---|---|---|---|---|
| Replay | Send an already used sign-in message and signature again | Refused: the message is marked used in one database step, so it works only once | None needed | **PASS** — `401 CHALLENGE_ALREADY_USED` | `LoginFlowIT.replay_resubmittingASuccessfulLoginIsRejected`, `challengeIsBurnedByAFailedAttempt`; `StepUpIT.aProofWorksOnce` |
| Expired challenge | Sign a message after it expired (120 s) | Refused | None needed | **PASS** — `401 CHALLENGE_EXPIRED` | `LoginFlowIT.expiredChallengeIsRejected`, `challengeIsStillValidJustBeforeItsTwoMinutesAreUp`; `StepUpIT.aProofExpires` |
| Wrong wallet | Sign with wallet A but claim to be wallet B | Refused | None needed | **PASS** — `401 SIGNATURE_MISMATCH` | `LoginFlowIT.signatureByAnotherRegisteredWalletIsAMismatch`; `SignatureVerifierTest.rejectsASignatureFromAnotherWallet` |
| Malformed input | Bad address, short signature, unknown JSON fields, body over 16 KB | Refused without internal details | None needed | **PASS** — `400` / `413`, no stack trace or SQL in the answer | `ErrorFormatIT.malformedBodiesAreRejectedWithoutLeakingParserDetails`, `missingBodyOrFieldsAreValidationOrFormatErrors`, `oversizedBodiesAre413WhetherDeclaredOrStreamed`, `wrongContentTypeIs415`; `WalletAddressesTest.rejectsMalformedAddresses`; `SignatureVerifierTest.rejectsWrongLengthAndNonHexInput` |
| Missing authentication | Call `/api/users/*` and `/api/admin/*` without signing in | Refused | None needed | **PASS** — `401 UNAUTHENTICATED` | `AuthorizationMatrixIT.anonymousCallersReachOnlyThePublicEndpoints` |
| CSRF | POST without the token, or from another website | Refused and logged | None needed | **PASS** — `403 CSRF_INVALID`, logged as `CSRF_REJECTED` | `CsrfIT.postWithoutOrWithAWrongTokenIsRejectedAndChangesNothing`, `foreignOriginIsRejectedEvenWithAValidToken`, `loginCsrfIsBlocked`, `tokenIsAcceptedInTheHeaderOnly` |
| Brute force | Send many requests or failed sign-ins from one address | Limited and blocked | None needed | **PASS** — the 11th request in a minute gets `429`; 10 failures in 15 min block the address for 15 min | `RateLimitIT.tenFailedVerificationsBlockTheIpForFifteenMinutes`, `verificationRequestsAreLimitedToTenAMinute`, `eleventhChallengeRequestInAMinuteIsLimitedDespiteSpoofedForwardedHeaders`; `InMemoryRateLimiterTest` |
| SQL injection and XSS | Put SQL or `<script>` text into fields, filters and paths | SQL text refused with `400` and the data stayed intact. Markup was refused by input rules or kept as plain text, but no test covered it | Added a test for markup | **PASS** — `400 VALIDATION_FAILED`, no `500`; markup refused or returned as JSON text | `ErrorFormatIT.injectionPayloadsAreValidationErrorsAndLeaveTheDatabaseIntact`; new `PathAndMarkupIT.markupIsRefusedByValidationOrKeptAsPlainText` |
| Unregistered or revoked wallet | Sign in with a wallet that is not registered, or was revoked | Refused | None needed | **PASS** — `403 WALLET_NOT_REGISTERED` / `403 WALLET_REVOKED` | `LoginFlowIT.unregisteredWalletWithAValidSignatureIsRefused`; `RevocationFlowIT.revokeEndsTheSessionAndBlocksLoginUntilReactivated`; `LoginServiceTest.revokedWalletIsRefused` |

### 3.2 Moderate attacks

| Attack | What It Tries to Do | Before Fix | Fix Applied | Final Result | Evidence |
|---|---|---|---|---|---|
| Privilege escalation | A normal user calls admin pages or asks to confirm an admin action | Refused and logged | None needed | **PASS** — `403 ACCESS_DENIED`, logged as `PRIVILEGE_ESCALATION_ATTEMPT` | `AuthorizationMatrixIT.userIsConfinedToUserEndpointsAndEscalationIsRecorded`; `StepUpIT.aUserGetsChallengesForTheirOwnActionsOnly` |
| Cross-user access (IDOR) | Use another user's session, device or alert ID | Refused; nothing changed | None needed | **PASS** — `404 RESOURCE_NOT_FOUND` | `SessionManagementIT.anotherUsersSessionsAndDevicesAreNotReachable`; `SignInAlertIT.anotherUsersAlertIsNotFound`; `HistoryScopeIT` |
| Session fixation | Plant a session ID before login and hope it gets signed in | A new ID is issued at login; a planted ID is never signed in | None needed | **PASS** | `LoginFlowIT.sessionFixation_plantedSessionIdIsNeverAuthenticated`, `loginRotatesAnExistingSession` |
| Use after revocation | Keep using a session after an admin revokes the wallet | The next request is refused | None needed | **PASS** — `401 SESSION_REVOKED` | `RevocationFlowIT.revokeEndsTheSessionAndBlocksLoginUntilReactivated` |
| Address-casing tricks | Lower case, upper case, wrong checksum, zero address | All spellings map to one wallet; a wrong checksum and the zero address are refused | None needed | **PASS** | `WalletAddressesTest.normalizesLowerUpperAndChecksummedFormsToOneIdentity`, `rejectsMalformedAddresses`; `LoginFlowIT.challengeIsBoundToItsWalletAndPurpose` |
| Signature malleability | Send the "high-s" twin of a valid signature | Refused | None needed | **PASS** — `INVALID_SIGNATURE` | `SignatureVerifierTest.rejectsTheMalleableHighSTwin` |
| Second-step abuse | Reuse an authenticator or backup code, guess more than 5 times, use an expired attempt | Refused each time | None needed | **PASS** | `SecondStepIT.anAuthenticatorCodeIsAcceptedOnlyOnce`, `aBackupCodeWorksOnceInAnyCommonSpelling`, `fiveWrongCodesEndTheAttempt`, `theAttemptExpiresAfterFiveMinutes`, `onlyTheBrowserThatSignedCanFinish` |
| Fresh-signature misuse | Use a confirmation signature for another action, wallet or session | Refused | None needed | **PASS** — `403 STEP_UP_FAILED` | `StepUpIT.aProofForOneActionCannotBeUsedForAnother`, `aProofForOneWalletCannotBeUsedForAnother`, `aProofIsBoundToTheSessionItWasIssuedTo` |
| API key abuse | Use an expired, revoked or changed key; try to write with a read-only key | Refused | None needed | **PASS** — `401 INVALID_API_KEY`; writes `403` | `ApiKeyIT.everyUnusableKeyIsRefusedTheSameWay`, `aRevokedKeyStopsWorkingAtOnce`, `aKeyExpires`, `aKeyCannotChangeAnythingEvenWithACsrfToken`, `aKeyReadsOnlyWhatItsScopesAllow` |
| Path tricks | `%76erify`, `;`, encoded slashes and `..` to slip past checks | Encoded names were tested and still rate limited. Other spellings were refused, but only `;` had a test | Added a test for the other spellings | **PASS** — encoded path still `429`; `;`, `%2e%2e`, `//`, `/../` give `400` with the JSON error body; `%2F`, `%5C`, `%00` give `400` from the web server itself (see 4.5) | `RateLimitIT.percentEncodedPathsCannotEscapeTheLimits`; new `PathAndMarkupIT.otherSpellingsOfAPathAreRefusedBeforeTheyReachAnEndpoint` |
| Fail-closed behaviour | Make the chain, the database or the risk check fail during sign-in | Sign-in refused (`503`), never allowed | None needed | **PASS** (failures simulated in tests; the real node and database were not switched off) | `LoginFlowIT.chainOutageFailsLoginClosedButKeepsExistingSessionsWorking`; `ErrorFormatIT.databaseFailureIsAGeneric503AndAuthenticationFailsClosed`; `FailClosedFiltersTest`; `TrustServiceTest.withoutASecondStepAFailedCheckRefuses`; `LoginServiceTest.unreachableChainFailsClosed` |

### 3.3 Hard attacks

| Attack | What It Tries to Do | Before Fix | Fix Applied | Final Result | Evidence |
|---|---|---|---|---|---|
| Concurrent single-use race | Use one challenge, one backup code or one authenticator code 50 times at the same moment | Protected by one-step database updates, but tested with only 2 parallel requests | Added a 50-way race test | **PASS** — in each race exactly 1 of 50 succeeded | New `SingleUseRaceIT` (50 threads each); `ChallengeConcurrencyIT`; `ApiKeyIT.oneConfirmationCreatesAtMostOneKeyEvenWhenSentTwiceAtOnce` |
| Operator-key compromise | Use the server's operator key to call the contract directly | **FAIL:** the emergency stop and the delays existed only in the backend; the contract obeyed its single, permanent admin key, which could reactivate any wallet at once | New contract: operator plus guardian, an on-chain delay for reactivations and unpauses, an on-chain pause tied to the emergency stop, and operator replacement by the guardian. The backend also cancels any request on the chain that it did not make (4.6) | **PARTIAL** — the key can no longer restore access at once or lift a pause, and the guardian can replace it; it can still revoke any wallet and, while not paused, register wallets | Contract test `a stolen operator key cannot restore access at once and is undone by the guardian`; `WalletRegistryClientIT.theGuardianCancelsWhatTheOperatorRequestsAndAPauseBlocksAccessGrants`; new `ChainRequestGuardIT` |
| Phishing lookalike site | Get the user to sign on a fake site | The message names the real site; another website's page cannot call the API; a fake site's server can still pass on real messages | None possible on the server | **OUT OF SCOPE** — only the wallet's domain warning and the user protect against a relaying fake site | `Eip4361FormatTest`, `ChallengeMessageFactoryTest`; `CsrfIT.loginCsrfIsBlocked`, `foreignOriginIsRejectedEvenWithAValidToken` |
| Stolen administrator session | Use a copied admin cookie to revoke wallets, create keys or lift the stop | Revoke, grant, key creation and stop changes needed the wallet's signature, but **cancelling a waiting action and revoking an API key did not** | Both now need a fresh signature naming the action or the key (4.8) | **PARTIAL** — every change now needs the admin's wallet; a thief can still read admin data and sign the owner out of other sessions | `AdminActionIT.cancellingNeedsAFreshSignatureNamingTheAction`; `ApiKeyIT.revokingAKeyNeedsAFreshSignatureNamingTheKey`; `StepUpIT.aStolenSessionCannotProduceTheWalletsSignature` |
| Database and chain drift | Change a wallet on the blockchain outside the app | **Gap confirmed:** an open session kept working until something read that wallet from the chain again, which could be never | Background chain check every 15 s (`ChainStatusReconciler`) | **PARTIAL** — the session now ends within about 15 seconds; new sign-ins were already refused at once | New `ChainStatusReconcilerIT.aRevocationMadeDirectlyOnTheChainEndsTheOpenSession`, `aChainResetEndsTheSessionsOfWalletsItNoLongerKnows`, `activeWalletsKeepTheirSessionsAndAnUnreachableChainChangesNothing` |
| Rate-limit evasion | Fake `X-Forwarded-For`; share one address behind the Docker proxy | **Gaps confirmed:** the `prod` profile trusted fake headers from any private network address; in Docker every user shared nginx's address | Only `TRUSTED_PROXIES` may report the client address; in Docker that is nginx's fixed address; nginx replaces any client-sent header (4.3, 4.7) | **PASS** — in the running Docker stack, a client that failed 10 times was blocked while another client was not, and a fake header was ignored | `RateLimitIT.eleventhChallengeRequestInAMinuteIsLimitedDespiteSpoofedForwardedHeaders`; new `ForwardedHeadersIT`, `TrustedProxyIT`; live Docker check (section 6) |
| Trust-score manipulation | Change IP or device to raise the score; make a victim look risky through a shared device | Works as designed: a new device and network still allow a key holder without a second step; a flagged wallet that shared a device lowers the victim's score | None (a design choice) | **PARTIAL** | `TrustScoreIT.aNewDeviceLowersTheScoreButASignatureAloneStillSuffices`, `sharingADeviceWithAFlaggedWalletLowersTheScore`, `withASecondStepALowScoreAsksForTheCodeInsteadOfRefusing` |
| Timeout and partial failure | A revocation transaction is mined after the server stopped waiting (30 s) | **Gap confirmed:** the server answered `504`, the database still said "active", and the revoked user's session stayed open | Same background chain check | **PARTIAL** — the session now ends within about 15 seconds of mining; sign-ins read the chain and are refused at once | New `ChainStatusReconcilerIT.aRevocationMinedAfterTheTimeoutStillEndsTheSession` |
| Denial of service | Flood sign-in endpoints or fill the challenge table | Limits per address work; many addresses are not limited together; limits live in memory | None (needs infrastructure such as a shared limiter or a firewall) | **PARTIAL** — not load-tested | `RateLimitIT` (per-address limits); `ChallengeCleanupIT` (expired challenges deleted hourly) |
| Seed theft or malicious extension | Steal the wallet's secret phrase, or run a bad browser extension | Whoever holds the key is, to the server, the owner | None possible on the server | **OUT OF SCOPE** — a second step adds a code; the server cannot protect a stolen key | Design of the protocol; `TrustScoreIT.withASecondStepALowScoreAsksForTheCodeInsteadOfRefusing` |

---

## 4. Bugs and security gaps found

### 4.1 Revoked wallets could keep an open session (drift)

- **What was wrong:** The server checks sessions against its own database copy of each wallet's status,
  not the blockchain, for speed. If the status changed on the blockchain without going through the app,
  the copy was not updated. A user who stayed signed in kept working until something happened to read
  that wallet from the chain again. If nothing did, the session lasted until it timed out.
- **Why it mattered:** A revocation is meant to cut access. Here it could be ignored for a signed-in user,
  for example after a direct call with the operator key.
- **Where:** session checks in `security/ActiveUserFilter.java`, which read `users.status` only.
- **How it was fixed:** A new background job, `session/ChainStatusReconciler.java`, runs every 15 seconds.
  For every wallet that has an open session it reads the blockchain, corrects the database copy, and ends
  the sessions of any wallet that is no longer registered and active. If the blockchain cannot be read, it
  changes nothing and tries again later (sessions do not depend on the chain).
- **How it was verified:** `ChainStatusReconcilerIT` (4 tests). The first test shows the gap: right after
  the outside revocation the session still answers `200`. After the job runs, the next request is
  `401 SESSION_REVOKED`, the database says `REVOKED`, and a new login is refused.

### 4.2 A revocation that is confirmed late left access open (timeout)

- **What was wrong:** The server waits 30 seconds for a revocation transaction. If it is mined after that,
  the admin sees `504 BLOCKCHAIN_TIMEOUT`, the database still says "active", and the user's session stays
  open, even though the blockchain now says "revoked".
- **Why it mattered:** The blockchain and the server disagreed about whether the user should have access.
- **Where:** `admin/AdminWalletService.revoke` and `blockchain/WalletRegistryClient` (30-second wait).
- **How it was fixed:** The same background check (4.1) sees the mined revocation and ends the session.
- **How it was verified:** `ChainStatusReconcilerIT.aRevocationMinedAfterTheTimeoutStillEndsTheSession`. The
  revocation is answered `504`, the session works right after, and it is refused after the job runs.

### 4.3 The `prod` profile trusted forwarded addresses from any private network

- **What was wrong:** In the `prod` profile the server reads the client address from the `X-Forwarded-For`
  header that a proxy adds. Tomcat's default list trusts that header from any private or local address
  (10.x, 172.16–31.x, 192.168.x, 127.x and more).
- **Why it mattered:** Anyone who could reach the backend port directly from such a network could write
  any address into the header. Rate limits and the 15-minute block are per address, so each request could
  pretend to be someone new.
- **Where:** `backend/src/main/resources/application-prod.yml`.
- **How it was fixed:** `prod` now trusts the header only from the addresses in the new `TRUSTED_PROXIES`
  setting, by default only this machine (`127.0.0.1/32, ::1/128`). Documented in `.env.example` and the
  README.
- **How it was verified:** `ForwardedHeadersIT` shows that a sender who is not a configured proxy cannot
  change its address (the 11th request is still `429`) and that `prod` has the safe default.
  `TrustedProxyIT` shows that a configured proxy still passes on the real client address.
- **Severity:** low. It needs the backend port to be reachable without going through the proxy.

### 4.4 Missing test coverage (no code defect)

The protections against `%2F`, `/../` and `//` paths, against markup in inputs and against 50 parallel
uses of one code already worked, but no test proved them. `PathAndMarkupIT` and `SingleUseRaceIT` now do.

### 4.5 Some refused URLs get the web server's own error page (informational, accepted)

- **What was found:** URLs containing `%2F` (an encoded `/`), `%5C` (an encoded `\`) or `%00` (a null
  byte) are refused by Tomcat, the web server inside the backend, before the application runs. The answer
  is a correct `400`, but it is a small HTML page instead of the API's JSON error. It also lacks the API's
  security headers.
- **Why it matters little:** the request never reaches an endpoint. The page holds no data, no server
  version and none of the request. In the Docker stack nginx adds its security headers to `/api/` answers
  anyway. Changing it would need a web-server customisation, and Tomcat refuses `%00` itself in any case.
- **What was done:** no code change. The README now says these URLs get a plain `400` page.
  `PathAndMarkupIT` checks that each one is refused with `400`.

### 4.6 One stolen operator key controlled every wallet (fixed in the second round)

- **What was wrong:** the contract had one admin, fixed forever: the backend's online operator key.
  That key could register, revoke and reactivate any wallet instantly. The emergency stop and the
  10-minute delay lived only in the backend, so a thief with the key skipped them.
- **Why it mattered:** one leaked secret meant full control of who may sign in, without warning.
- **How it was fixed:** `contracts/contracts/WalletRegistry.sol` now has two keys.
  - The **operator** (the backend's key) registers and revokes at once.
  - A reactivation must be **requested**, wait the contract's delay (600 s by default), then be
    **executed**. While it waits, the operator or the guardian can cancel it.
  - The contract can be **paused** (by either key). While paused, nothing can be registered or
    reactivated. Unpausing also has to be requested and wait.
  - The **guardian** is a separate key kept offline (on a real network, a hardware wallet or a
    multisignature wallet). Only it can replace the operator, and replacing the operator voids every
    request the old one left waiting.
- **Backend changes:**
  - Reactivation requests start the on-chain wait at the same time as BlockAuth's own wait.
  - Cancelling in BlockAuth cancels on the chain too.
  - The emergency stop pauses the contract, and lifting it waits for the on-chain unpause.
  - The startup check refuses a contract whose delay is longer than `ADMIN_ACTION_DELAY`.
  - A new background check (`ChainRequestGuard`) runs every 15 seconds. It cancels any reactivation or
    unpause request on the chain that BlockAuth did not make, records `UNEXPECTED_CHAIN_REQUEST`, and pauses
    the contract again if the emergency stop is on.
  - Migration `V3` allows the new event type.
  - `contracts/scripts/guardian.js` runs the guardian's actions.
- **How it was verified:**
  - 21 contract tests, including one that plays the attack with the operator key.
  - 12 tests against a real local chain.
  - 5 new `ChainRequestGuardIT` tests.
  - The full suite (section 6).
- **What remains:** see section 7. A stolen operator key can still revoke wallets (a denial of service,
  undone by reactivation after the delay) and register wallets while the contract is not paused. Whoever
  holds both keys controls the registry.
- **Spec change:** the contract interface differs from the one the project specification defines
  (`activateWallet` and `admin` are replaced). This is a deliberate deviation to accept or revert.

### 4.7 Behind the Docker proxy everyone shared one address (fixed in the second round)

- **What was wrong:** in Docker Compose every request reaches the backend from nginx. The backend did not
  use forwarded headers there, so all users had nginx's address. Ten failed sign-ins by one person blocked
  everyone for 15 minutes.
- **How it was fixed:**
  - `TRUSTED_PROXIES` moved to `application.yml`, so it works in every profile.
  - `docker-compose.yml` gives nginx a fixed address (`172.29.53.10` on its own network), turns forwarded
    headers on for the backend, and trusts only that address.
  - `frontend/nginx.conf` now sends only the address it saw (`$remote_addr`) instead of adding to whatever
    the client sent.
- **How it was verified (running Docker stack, test database):**
  - Client A failed 10 sign-ins and was then refused with `429 IP_TEMPORARILY_BLOCKED`.
  - Client B, a different address behind the same nginx, could still request a challenge (`201`).
  - Client C sent 10 failures with a fake `X-Forwarded-For` header and was blocked under its own real
    address.
  - `ForwardedHeadersIT` and `TrustedProxyIT` cover the setting.
- **Note:** with Docker Desktop, a browser on the same computer reaches nginx through Docker's port
  forwarding and is seen as the network gateway (`172.29.53.1`). All such browsers are the same machine.

### 4.8 A stolen admin session could cancel actions and revoke API keys (fixed in the second round)

- **What was wrong:** `POST /api/admin/actions/{id}/cancel` and `POST /api/admin/api-keys/{id}/revoke`
  needed only the session cookie. A thief with a copied admin cookie could block a needed reactivation or
  the lifting of the emergency stop, and cut off monitoring scripts.
- **How it was fixed:** two new fresh-signature actions, `CANCEL_ADMIN_ACTION` and `REVOKE_API_KEY`. The
  message the admin signs names the action (for example `number 12 (ACTIVATE_WALLET for 0x…)`) or the key
  (name and id), read from the database. Both endpoints now require a `proof`, and the admin page asks
  MetaMask for it. The Postman request for cancelling was updated to send a proof.
- **How it was verified:**
  - New tests: `AdminActionIT.cancellingNeedsAFreshSignatureNamingTheAction` and
    `ApiKeyIT.revokingAKeyNeedsAFreshSignatureNamingTheKey`.
  - Without a proof the answer is `400`.
  - With a signature from another wallet, or a proof for a different action or key, the answer is
    `403 STEP_UP_FAILED` and nothing changes.

### 4.9 A failed emergency stop could leave the contract paused (fixed on 24 October)

- **What was wrong:** switching the emergency stop on paused the contract first and saved the stop in the
  database afterwards. If the save failed, or a second administrator's request lost the race, the
  contract stayed paused while BlockAuth showed the stop as off. Registrations and reactivations were then
  refused with a misleading `502 BLOCKCHAIN_TX_FAILED`, and the page offered no request to lift the pause.
- **How it was fixed:** `admin/EmergencyStopService.activate` pauses the contract only after the stop is
  saved. If the chain cannot be paused then, the stop still holds in BlockAuth and `ChainRequestGuard`
  pauses the contract on its next run (unchanged).
- **How it was verified:** new `EmergencyStopServiceTest` (2 tests). Both fail on the old order and pass
  on the new one. The full suite passed afterwards (section 6).

---

## 5. Security fixes made

| File | Change |
|---|---|
| `backend/src/main/java/com/blockauth/session/ChainStatusReconciler.java` | **New.** Every 15 s, reads the chain for wallets with open sessions, fixes the database copy, ends sessions of wallets that are no longer active, records `SESSION_TERMINATED`. |
| `backend/src/main/java/com/blockauth/session/UserSessionRepository.java` | New query `findUsersWithOpenSessions`. |
| `backend/src/main/resources/application.yml` (second round; first round put it in `application-prod.yml`) | `server.tomcat.remoteip.internal-proxies` set from `TRUSTED_PROXIES`, default `127.0.0.1/32, ::1/128`. |
| `contracts/contracts/WalletRegistry.sol`, `contracts/scripts/deploy.js`, `contracts/scripts/guardian.js` (new), `contracts/test/WalletRegistry.test.js` | Second round: operator and guardian keys, on-chain delay and pause, operator replacement (4.6). |
| `blockchain/WalletRegistryClient.java`, `blockchain/BlockchainStartupVerifier.java`, `admin/AdminWalletService.java`, `admin/AdminActionService.java`, `admin/EmergencyStopService.java`, `admin/ChainRequestGuard.java` (new), `admin/AdminActionRepository.java`, `common/ErrorCode.java`, `audit/AttackType.java`, `db/migration/V3__unexpected_chain_request.sql` (new) | Second round: the backend side of 4.6. |
| `auth/StepUpAction.java`, `auth/StepUpController.java`, `admin/ControlsController.java`, `apikey/ApiKeyController.java`, `apikey/ApiKeyService.java`, `frontend/src/pages/AdminControls.jsx`, `postman/BlockAuth.postman_collection.json` | Second round: fresh signature for cancelling and for revoking API keys (4.8). |
| `docker-compose.yml`, `frontend/nginx.conf` | Second round: per-client addresses behind nginx (4.7). |
| `backend/src/main/resources/application-test.yml` | The new job's schedule is switched off in tests; tests call it directly. |
| `.env.example`, `README.md` | `TRUSTED_PROXIES` documented; the 15-second chain check and its remaining window documented; the plain `400` page for `%2F`, `%5C`, `%00` noted. |
| `CompleteDetails.md` | Sections about drift, timeouts and proxy trust updated to match. |
| `backend/src/test/java/com/blockauth/auth/LogoutIT.java` | Test stability only: `idleSessionExpires` now accepts `SESSION_REVOKED` as well as `UNAUTHENTICATED` (both are `401`), because of a rare Tomcat timing race explained in section 6. Its other checks are unchanged. |
| `backend/src/main/java/com/blockauth/admin/EmergencyStopService.java`, new `admin/EmergencyStopServiceTest` (24 Oct) | The contract is paused only after the emergency stop is saved (4.9). |
| Tests (new) | `session/ChainStatusReconcilerIT`, `auth/SingleUseRaceIT`, `security/ForwardedHeadersIT`, `security/TrustedProxyIT`, `security/PathAndMarkupIT`; helper `Api.status` for answers that are not JSON. |

No existing protection was removed or weakened, and no dependency was added. The second round changes the
contract's interface (a spec deviation, see 4.6) and adds one migration (`V3`).

---

## 6. Tests performed

All runs were on 21 October 2026, on this computer, against the Neon `test` branch and a local Hardhat
node.

| Test run | Command | Result |
|---|---|---|
| New and related tests while fixing | `mvnw verify -Dit.test=ChainStatusReconcilerIT,SingleUseRaceIT,ForwardedHeadersIT,TrustedProxyIT,RevocationFlowIT,RateLimitIT` | 21 tests, 0 failures |
| Full backend suite, including tests against the real local blockchain | `mvnw clean verify -Pblockchain-it` | Final run: 184 unit tests and 183 integration tests (10 of them against the real local blockchain), 0 failures, 0 errors, 0 skipped |
| Path and markup test | `mvnw verify -Dit.test=PathAndMarkupIT` | 2 tests, 0 failures (after the test was corrected to expect Tomcat's own page for `%2F`, `%5C`, `%00`; see 4.5) |
| Smart contract tests | `npx hardhat test` | 15 passing |
| Frontend build | `npm run build` | Built successfully |
| Second round: smart contract tests | `npx hardhat test` | 21 passing (new two-key contract, including a test that plays the stolen-operator-key attack) |
| Second round: full backend suite with the real local blockchain | `mvnw clean verify -Pblockchain-it` | 187 unit tests and 192 integration tests (12 against the real chain), 0 failures, 0 errors, 0 skipped |
| Second round: Docker Compose stack (test database) | three client containers behind nginx | Client A: ten `401`, then `429 IP_TEMPORARILY_BLOCKED`; client B: `201`; client C with a fake `X-Forwarded-For`: blocked under its own address |
| Second round: frontend build | `npm run build` | Built successfully |
| 24 Oct: smart contract tests | `npx hardhat test` | 21 passing |
| 24 Oct: full backend suite with the real local blockchain, after the 4.9 fix | `mvnw clean verify -Pblockchain-it` | 189 unit tests and 192 integration tests, 0 failures, 0 errors, 0 skipped |
| 24 Oct: every test named in section 3 | script check of `backend/src/test` and `contracts/test` | All 67 cited test methods and 13 cited test classes exist (and ran in the suite above) |
| 24 Oct: Postman collection | `npx --yes newman@6 run postman/BlockAuth.postman_collection.json -e <local env>` against the backend on the `test` branch and a local Hardhat node, with USER and ADMIN sessions from real signed logins of Hardhat test wallets | Exit code 0; 86 requests, 222 assertions, 0 failed, nothing skipped |
| 24 Oct: live attacks against the running app and the real local chain | script with Hardhat test wallets | All passed: replay `401 CHALLENGE_ALREADY_USED`; registration message used for login `401 INVALID_CHALLENGE`; another wallet's signature `401 SIGNATURE_MISMATCH`; high-s twin `401 INVALID_SIGNATURE`; user on an admin API `403 ACCESS_DENIED`; expired message after 125 s `401 CHALLENGE_EXPIRED`; a revocation sent straight to the contract with the operator key ended the open session after 13 s (`401 SESSION_REVOKED`) and the next login was `403 WALLET_REVOKED`; a reactivation request sent straight to the contract with the operator key was cancelled on the chain after 13 s and recorded as `UNEXPECTED_CHAIN_REQUEST`; the contract refused `executeActivation` before its delay (`TooEarly`); each of these was found in `security_events` |

One problem came up during the runs. The first complete run after the fixes had one failure:
`LogoutIT.idleSessionExpires` got `401 SESSION_REVOKED` instead of `401 UNAUTHENTICATED`. The log shows that
no other part of the test setup was running and that the new background check never ran on its own. The
cause is timing inside Tomcat: while Tomcat's background thread is expiring an idle session, it still
reports that session as valid, though its record is already closed. A request arriving in that moment is
refused by the session check instead. The request is refused either way, and the record is closed as
`EXPIRED` either way. The test now accepts both codes, and the full run was repeated.

What was **not** run, and why (first round; the Postman collection was run on 24 October, see above):

- **Postman collection (84 requests):** it needs a running stack and session cookies copied from a
  browser. Not run in the first round.
- **OWASP ZAP:** not run again. The earlier results are in `security-tests/zap/README.md`: no High or
  Medium alerts, last run 19 October 2026.
- **Burp Suite:** not installed on this machine.
- **A separate attack script** sending live attacks at the running app was started but not used. The same
  attacks are covered by the automated integration tests above, which send real HTTP requests with real
  wallet signatures to the running application.
- **Docker Compose stack:** not started in the first round. The second round tested the proxy fix in the
  running Docker stack (4.7); it was not run again on 24 October.
- **Switching off the real node or database during sign-in:** simulated in tests instead.

---

## 7. Known limitations

These risks remain. None of them is claimed as fixed.

- **Operator-key compromise (reduced, not gone).** With the stolen key alone, a thief:
  - **cannot** restore access at once, or lift a pause at once;
  - **can** revoke any wallet at once (a denial of service, undone by reactivating after the delay);
  - **can** register new wallets while the contract is not paused;
  - **can** keep making requests. While BlockAuth runs, its background check cancels them every 15
    seconds. If BlockAuth is down, only the guardian can cancel them.

  The guardian must replace the stolen key. Whoever holds both keys has full control. On the local chain
  both are public Hardhat test keys; a real deployment needs a guardian key that is kept offline or is a
  multisignature wallet. The contract is not audited.
- **Phishing lookalike sites.** A fake site can ask BlockAuth for a real sign-in message and show it to
  the user. The message names the real site (`localhost:5173 wants you to sign in…`), and wallets warn
  when it does not match the page. If the user ignores the warning and signs, the fake site can sign in
  as them.
- **Stolen seed phrase or malicious browser extension.** Whoever holds the wallet's key is the owner, as far
  as the server can tell. A second step adds a code requirement, but only if the user turned it on.
- **Stolen administrator session.** Without the admin's wallet the thief cannot change anything any more
  (since 4.8). The thief can still read all admin data (including IP addresses) and sign the owner out of
  other sessions.
- **Docker proxy.** Fixed in 4.7 for clients with their own address. With Docker Desktop, browsers on the
  same computer all appear as the Docker gateway address, but they are one machine anyway. The
  `172.29.53.0/24` network must be free on the host.
- **In-memory limits.** Rate-limit counters, the 15-minute block, sessions and waiting second-step attempts
  live in the server's memory. A restart clears them, and two server copies would not share them.
- **Many addresses.** An attacker with many IP addresses (easy with IPv6) is not slowed down by
  per-address limits. Each challenge they request adds a database row until the hourly cleanup removes
  rows 24 hours after they expire.
- **Blockchain/database delay.** A change made outside the app reaches open sessions within about 15
  seconds, not instantly. The admin list can show an old status for a wallet with no open session until
  its status is read again. If the blockchain cannot be reached, the background check waits and open
  sessions continue.
- **Trust score.** The rules are fixed and not tuned. Without a second step, someone with the key on a new
  device and network still gets in (score 50). A flagged wallet that once shared a browser with a victim
  lowers the victim's score.
- **Local blockchain only.** Everything was tested on a local Hardhat chain. Real network fees, slow blocks
  and chain reorganisations were not tested.

---

## 8. Final security summary

- **Blocked (PASS), 22 rows:** replay, expired challenge, wrong wallet, malformed input, missing
  authentication, CSRF, brute force, SQL injection and XSS, unregistered or revoked wallets, privilege
  escalation, cross-user access, session fixation, use after revocation, address-casing tricks, signature
  malleability, second-step abuse, fresh-signature misuse, API key abuse, path tricks, fail-closed
  behaviour, the concurrent single-use race, and rate-limit evasion (fixed in the second round).
- **Partly addressed (PARTIAL), 6 rows:** operator-key compromise (much reduced in the second round),
  stolen administrator session (only reading and signing out remain), database and chain drift, and
  late-confirmed revocations (closed within about 15 s), trust-score manipulation, denial of service.
- **Not protected (FAIL):** none after the second round.
- **Outside the server's reach (OUT OF SCOPE), 2 rows:** phishing lookalike sites, stolen seed phrases and
  malicious extensions.
- **Not tested:** a new ZAP scan, Burp Suite, switching off the real node or database, and the Account
  security and Controls screens with the real MetaMask extension (they were exercised on 24 October with a
  stand-in wallet signing through the local node). On 24 October the Postman collection was run (0 of 222
  assertions failed), and the real MetaMask extension was used for registration, login, account switching
  while signed in, a rejected signature, and revoke and delayed reactivation. The Docker stack was tested only for the proxy fix (4.7). The attacks they would cover were tested in other ways where shown above.

The first round found three real weaknesses (4.1 to 4.3; the first two share one fix). The second round
fixed three more serious risks (4.6 to 4.8). Every fix has a test. It also found one informational issue that was accepted (4.5). It does not prove that
BlockAuth has no other weaknesses; no test or review can prove that.
