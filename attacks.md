# BlockAuth: Attack Test Plan

Attacks to test, from basic to hard. Run only on your own local stack.

## 1. Basic (should always be refused)

| Attack | What to try | Expected result |
|---|---|---|
| Replay | Resubmit a used signature or challenge | Rejected; challenge is single-use |
| Expired challenge | Sign after the expiry (default 120 s) | Rejected |
| Wrong wallet | Sign with wallet A, claim wallet B | SIGNATURE_MISMATCH |
| Malformed input | Bad address, short signature, unknown JSON fields, body over 16 KB | 400 or 413 |
| Missing authentication | Call /api/users/\* and /api/admin/\* with no session | 401 |
| CSRF | POST with no token, or with a foreign Origin | Rejected; logged as CSRF_REJECTED |
| Brute force | Repeat requests from one IP | 429, then IP block after 10 failures in 15 minutes |
| SQL injection and XSS strings | Send them in every parameter | No SQL errors or stack traces |
| Unregistered or revoked wallet | Sign in with each | WALLET_NOT_REGISTERED or WALLET_REVOKED |

## 2. Moderate

| Attack | What to try | Expected result |
|---|---|---|
| Privilege escalation | USER session calls admin endpoints or admin step-up actions | 403; logged as PRIVILEGE_ESCALATION_ATTEMPT |
| Cross-user access (IDOR) | Use another user’s session, device or alert ID | 404 |
| Session fixation | Compare session ID before and after login | A new ID is issued |
| Use after revocation | Revoke a wallet that has an open session | Next request returns 401 |
| Address-casing tricks | Mixed or lower case, bad EIP-55 checksum, zero address | Normalised or refused |
| Signature malleability | Resubmit with the high-s form | Rejected |
| Second-step abuse | Reuse a TOTP or backup code, exceed 5 tries, let it expire | Refused each time |
| Fresh-signature misuse | Use a signature for a different action, parameter or session | STEP_UP_FAILED |
| API key abuse | Expired, revoked or altered keys; write attempt with a read-only key | 401 or 403 |
| Path tricks | %76erify, “;” or encoded slashes in URLs | Still rate limited, or 400 |
| Fail-closed behaviour | Stop the chain node or database, or break the risk query | Sign-in fails, never allowed |

## 3. Hard

<!-- Original PDF page 1 footer: 1; the Hard table begins on page 2. -->

| Attack | What to try | Why it is hard / expected |
|---|---|---|
| Concurrent single-use race | 50 parallel submissions of one challenge, backup code or TOTP window | Exactly one should succeed |
| Operator-key compromise | Call the contract directly with the operator key | Bypasses emergency stop and delays; a documented weakness, report honestly |
| Phishing lookalike site | Request a signature on a mismatched domain | Only the wallet warning protects the user |
| Stolen administrator session | Take over an admin session; revoke, create keys, lift the stop | Fresh signatures and delay should stop it |
| Database and chain drift | Change wallet status on chain outside the app | Open session survives until next chain read |
| Rate-limit evasion | Spoof X-Forwarded-For; share one IP behind the Docker proxy | In Docker all users share nginx’s address |
| Trust-score manipulation | Rotate IPs and devices; get a victim’s wallet flagged via shared devices | Score uses shared-device links |
| Timeout and partial failure | Revoke when the transaction is mined after the 30 s timeout | Mirror stale until next chain read |
| Denial of service | Flood sign-in endpoints or the pending-challenge table | Limits are in memory and per IP |
| Seed theft or malicious extension | Out of server scope; state it as a limit | Stolen seed gives full access |

## Tools

OWASP ZAP (already used), the Postman collection (84 requests), Burp Suite for tampering and races, and a short script for race and replay tests. Record only results you have actually run before adding them to the paper.

<!-- Original PDF page 2 footer: 2. -->
