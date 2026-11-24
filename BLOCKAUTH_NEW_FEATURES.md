# BlockAuth: new features and the risk-scoring system

Written on 24 November 2026, after a live retest of the running application.

## How "new" was decided

I compared the code with the Git history:

- **New:** the 21 commits on the branch `feature/risk-scoring-hardening` (23 November 2026, 18:32 to 23:29), plus the fixes made on
  24 November during live testing (not committed yet).
- **Not new:** everything that was already in the project on 19 October 2026 (commit `29f5034`). That includes:
  - wallet sign-in;
  - the rule-based trust score;
  - the second step (authenticator codes);
  - API keys;
  - the emergency stop;
  - the list of signed-in sessions and devices;
  - the admin pages.

  The earlier local work saved in the snapshot commit `b35be9f` (for example the contract guardian and the pause switch) is also not
  counted as new here.

Old features are described below only where a new feature builds on them, and they are marked as such.

## Test result words

| Word | Meaning |
|---|---|
| **PASS** | I ran it in the live application and checked the result |
| **PARTIALLY PASSED** | Some parts were checked live, others were not |
| **NOT VERIFIED** | Not checked live in this retest (the reason is given) |
| **BLOCKED** | Could not be tested, because something it needs is missing |

## How the live test was run

- **Backend:** two copies of the BlockAuth server, sharing one Redis store.
- **Database:** the **test** database. The main database was not touched.
- **Blockchain:** a local blockchain with the BlockAuth contract.
- **E-mail:** a local mail catcher called Mailpit, so no e-mail left the computer.
- **Browser:** a real Chrome browser driven by an automatic test.

Two things were imitated:

- **The MetaMask wallet.** A small stand-in made real signatures with real test keys, but the MetaMask extension itself was not used.
- **"Different networks".** Different internet addresses were sent through the local proxy, which the server only trusts from this computer.

Screenshots are in `BlockAuth-Feature-Screenshots/`. They are 1920 × 1080 pictures taken from the running app.

---

## Part 1: The risk-scoring system

### What risk scoring means

Every time someone signs in, BlockAuth asks: "Does this sign-in look like this person's usual sign-ins?" It answers with a number
from 0 to 100, called the **trust score**. 100 means "nothing unusual". The lower the number, the more unusual the sign-in looks.

### Why BlockAuth uses it

In BlockAuth there is no password: you sign in by signing a message with your crypto wallet. If someone steals the wallet's secret
key, they can sign that message too. The trust score is a second line of defence. A sign-in from a strange place can be stopped, or
asked for an extra code, even when the signature itself is correct.

### How it checks a sign-in

The score is only calculated after the wallet's signature has been checked and found correct. BlockAuth then looks at these things:

| Check | What it looks for | Points taken off |
|---|---|---|
| New device | A browser this wallet has never used before | 30 |
| New network | An internet address this wallet has never used before | 20 |
| Flagged wallet | An administrator marked the wallet as suspicious | 35 |
| Repeated failures | 3 or more failed attempts from the same internet address in the last hour | 25 |
| Linked to a flagged wallet | This wallet has used a device that a flagged wallet also used | 35 |

The score starts at 100 and these points are taken off.

> **Example.** Priya signs in from her laptop at home, as always. Nothing is unusual, so her score is **100**. Next day she signs in
> from a new phone on a café's Wi-Fi: new device (−30) and new network (−20), so her score is **50**.

### How the decision is made

| Trust score | Wallet **without** the second step | Wallet **with** the second step |
|---|---|---|
| 90 to 100 | Allowed | Allowed |
| 50 to 89 | Allowed | Asked for a code from the authenticator app |
| Below 50 | **Refused** | Asked for a code from the authenticator app |

> **Example.** Someone tries three unknown wallets from one address and fails each time. Then they try Priya's wallet from that same
> address, on a new device: new device (−30), new network (−20), repeated failures (−25) gives a score of **25**. Priya has no
> second step, so the sign-in is **refused**. The page says: "This sign-in was refused because it does not look like your usual
> sign-ins."

The rule-based score and the decisions above are an **older feature**. What is **new** is the learned model, the way the two can be
combined, and the new admin screen, all explained next.

### The learned model (new)

Next to the fixed rules there is now a small machine-learning model called an **anomaly model** (technically an "Isolation Forest").
It does not use fixed rules. It looks at 15 facts about each sign-in, for example:

- the time of day and the day of the week;
- how old the wallet is;
- how long ago the last sign-in was;
- how many sign-ins there were in the last day;
- how many different networks and devices were used recently;
- whether the device or network is new;
- whether the browser changed.

From these it gives a number from 0 to 1: about **0.5 means ordinary**, and close to **1 means very unusual**. It also names the facts
that made it unusual. They are shown as "Unusual in", for example "New network, Wallet age days". "Hour cos" in that list means the
time of day.

The model is trained again every night. Until enough real sign-ins exist, it is trained on 2,000 **made-up** sign-ins that describe
normal behaviour. The admin screen says so ("2000 synthetic sign-ins (cold start)").

### How the rules and the model work together (new)

An administrator chooses one of three modes. **Shadow** is the default.

| Mode | What happens |
|---|---|
| Off | Only the rules count |
| **Shadow** | The model's number is saved and shown to administrators, but it **never changes a decision** |
| Enforce | An unusual sign-in can **lose up to 30 more points**. The model can never add points |

In Enforce mode the points taken off grow with how unusual the sign-in is, above a set threshold:

- below the threshold, nothing is taken off;
- at 1.0 (a clear outlier), the full 30 points are taken off.

> **Example from the live test (Enforce mode, threshold 0.5).** A sign-in from a new device on a new network has a rule score of 50.
> The model rated it 0.64, which takes off 8 points, so the final score is **42**. 42 is below 50, so the sign-in was **refused**.
> In Shadow mode the same sign-in would have been allowed with 50.

### How users and administrators see the result

- **The user** sees the decision on the sign-in page:
  - they go straight in;
  - they are asked for a code ("Enter your verification code");
  - or they are refused, with a short explanation.

  Screenshots 04 and 05 show these screens.
- **Administrators** see every sign-in under *Security monitoring → Sign-in risk*:
  - the score from the rules;
  - the model's number;
  - the final score;
  - the decision, and why;
  - what the model found unusual;
  - the model version and mode.

  They can filter by decision, by how unusual it was, and by wallet. A model card shows the mode, the version, when the model was
  trained and on what data. Screenshots 01, 02 and 03 show this screen.

### Risk-scoring test results (live, 24 November 2026)

Every row was checked in three places:
- in the browser;
- through the admin API;
- directly in the database.

All three matched.

| Situation tested | What the system found | Score | Decision | Result |
|---|---|---|---|---|
| First sign-in after registering | nothing unusual | 100 | Allowed | PASS |
| Same device, same network | nothing unusual | 100 | Allowed | PASS |
| New device | new device | 70 | Allowed | PASS |
| New network | new network | 80 | Allowed | PASS |
| New device and new network | both | 50 | Allowed | PASS |
| 3 failures from one address, then a new device there | new device, new network, repeated failures | 25 | **Refused** | PASS |
| New device, second step turned on | new device | 70 | Code asked; a real code and a backup code both worked; a wrong code was refused | PASS |
| Flagged wallet on a new device | new device, flagged wallet | 35 | **Refused** | PASS |
| Flagged wallet on its usual device | flagged wallet | 65 | Allowed | PASS |
| Wallet used on the flagged wallet's browser, then on a new device | new device, linked to a flagged wallet | 35 | Code asked | PASS |
| Learned model in Shadow mode | model number saved, decision unchanged | final = rule score | as above | PASS |
| Learned model in Enforce mode | new device, new network, unusual | 50 → 42 | **Refused** (exact value checked) | PASS |
| How good the model is on real people's sign-ins | — | — | — | NOT VERIFIED (it has only seen made-up data so far) |

---

## Part 2: Newly added features

### 1. Learned risk model (anomaly model)

- **What it does:** Gives every sign-in a second opinion: a number for how unusual it looks, and which facts make it unusual.
- **Why it is useful:** Fixed rules only catch what someone thought of in advance. The model can notice unusual combinations, such as
  a sign-in at 4 a.m. from a brand-new wallet that just signed in somewhere else.
- **How it works:** It is trained on examples of normal sign-ins (made-up ones for now) and measures how far a new sign-in is from
  them. It is retrained every night, and all servers pick up the newest version. If the model fails or is missing, the rules decide
  alone, so a sign-in is never let through because of a model error.
- **How it was tested:** Live in Shadow mode (all scenarios above), and live in Enforce mode with the exact penalty checked. The model
  card and the "Unusual in" reasons were checked on screen.
- **Result: PASS.** Limitation: it is trained only on made-up data, and a brand-new wallet looks slightly unusual to it (finding
  A-33). Keep it in Shadow mode until it has been retrained on real sign-ins.
- **Screenshots:** `03-learned-risk-model-combined-score.png`, and the "Anomaly" and "Unusual in" columns in `01`.

### 2. Combined score (Enforce mode)

- **What it does:** Lets the learned model take points off the rule score, never add them.
- **Why it is useful:** Unusual sign-ins can be stopped or asked for a code even when no single rule fired strongly.
- **How it works:** final score = rule score − penalty. The penalty is 0 below the threshold and at most 30 points.
- **How it was tested:** The server was restarted in Enforce mode with threshold 0.5. The test signed in with a new device on a new
  network, then checked the final score and decision against the formula: 50 → 42, refused. A first sign-in went from 100 to 93,
  still allowed.
- **Result: PASS.**
- **Screenshot:** `03-learned-risk-model-combined-score.png`.

### 3. Sign-in risk screen for administrators (improved)

- **What it does:** Lists every scored sign-in with:
  - the rule score, the model's number, the final score and the decision;
  - the reasons;
  - what the model found unusual;
  - the model version and mode.

  It has filters and a model card.
- **Why it is useful:** Administrators can see why someone was refused or asked for a code, and can watch what the model would do
  before switching it on.
- **How it works:** It reads the saved risk assessments from the database through the admin API. The tab existed before; the model
  columns, filters and model card are new. Today the Decision column was moved next to the score, so it is visible on a laptop
  screen (finding A-38).
- **How it was tested:** Live, for one user (11 rows) and for all refused sign-ins. The values on screen were compared with the
  database.
- **Result: PASS.**
- **Screenshots:** `01-risk-scoring-assessment.png`, `02-risk-monitoring-refused-sign-ins.png`.

### 4. E-mail alerts for sign-ins on a new device

- **What it does:** If the user turns it on, BlockAuth sends an e-mail when their wallet signs in on a device it has never used.
- **Why it is useful:** The user learns about a stranger's sign-in even when they are not looking at BlockAuth.
- **How it works:**
  - The user enters an e-mail address and confirms it with a 6-digit code sent to that address.
  - The address is stored encrypted and shown masked (`l***@example.test`).
  - The alert contains no links, so it cannot be used for phishing. It only shows the device, the time and part of the network
    address (`198.51.100.x`).
  - Sending the mail never delays or blocks the sign-in.
- **How it was tested:** Live:
  - the address was set and confirmed with the code from the real e-mail;
  - a new-device sign-in followed, and the alert arrived in Mailpit;
  - the text was checked (no links, masked network);
  - the database was checked (address stored encrypted, sends recorded).
- **Result: PASS.** The "Stop e-mail alerts" button was not clicked live (it is covered by automatic tests).
- **Screenshots:** `06-new-device-alert-email.png`, `07-alert-email-setup-confirmed.png`.

### 5. Redis: several servers working as one (optional)

- **What it does:** When the `redis` setting is on, several BlockAuth servers share:
  - sign-in sessions;
  - speed limits;
  - a lock for blockchain writes;
  - a lock for nightly jobs.
- **Why it is useful:** BlockAuth can run on more than one server without users being signed out or limits being dodged by switching
  servers.
- **How it works:** The shared information is kept in Redis, a fast shared store, under keys that start with `blockauth:`. The
  fixes made today made sure the sessions really use that prefix (A-36), and made logging out reliable while other requests are still
  running (A-35).
- **How it was tested:** Live, with two servers:

  | Part | Check | Result |
  |---|---|---|
  | Shared sessions | A session started on server A was used on server B | PASS |
  | Shared speed limit | Six sign-in requests alternated between A and B: 5 allowed, the 6th refused, so the limit is shared | PASS |
  | Blockchain lock | 20 registrations at the same time on both servers: 20 succeeded, 0 transaction-number clashes | PASS |
  | Job lock | A delayed admin action was carried out once, by one server only | PASS |
  | Redis outage | What happens when Redis goes down | NOT VERIFIED live (covered by automatic tests) |

- **Result: PASS** for the four checked parts.
- No screenshot: this works behind the scenes and nothing on screen would prove it.

### 6. Blockchain writes ready for a public network

- **What it does:** Writes to the blockchain (for example revoking a wallet) use the modern Ethereum transaction type (EIP-1559). They
  wait for confirmations, give up waiting after a set time instead of hanging, and show the transaction hash with a Copy button.
- **Why it is useful:** This is needed before BlockAuth can run on a real network such as Sepolia, and it lets an administrator look up
  the transaction.
- **How it works:** The server signs the transaction itself and sends it. The page shows the hash; a link to a block explorer appears
  when one is configured.
- **How it was tested:** Live, on the local blockchain:
  - an administrator revoked a wallet;
  - the test read the transaction back from the blockchain: type 2 (EIP-1559), status success;
  - it asked the contract whether the wallet is active: no;
  - the wallet's next sign-in was refused.
- **Result: PASS** on the local blockchain. **Sepolia: BLOCKED.** No Sepolia address or funded key is configured.
- **Screenshot:** `08-blockchain-transaction-eip1559.png`.

### 7. API key limit taken from the server

- **What it does:** The "Valid for (days)" field on the API key form shows the limit that the server really uses.
- **Why it is useful:** The form can no longer suggest a value that the server would refuse.
- **How it was tested:** Live: the hint "1 to 90." matched the server's answer (90 days). Then a key was created, used within its
  permission (allowed), used outside it (refused), and revoked (refused afterwards).
- **Result: PASS.** This step also re-checked today's fix A-37: API keys did not work at all with Redis before it.

### 8. Frontend reliability improvements

| Part | What it does | How it was tested | Result |
|---|---|---|---|
| One click, one request | A double or triple click sends the sign-in only once | Live: a triple click sent one request | PASS |
| Session re-check | When you come back to a tab, it checks whether your session still exists | Live: "Sign out everywhere else" on a second device, then the first tab was focused again: it went to the login page, and could sign in again at once | PASS |
| Time limits on requests | A stuck request ends with a clear message instead of a spinner forever | Automatic tests only | NOT VERIFIED live |
| No out-of-date results | Changing a filter quickly never shows an old answer | Automatic tests only | NOT VERIFIED live |

**Overall: PARTIALLY PASSED** (two of the four parts were checked live).

### 9. Contract security checks

- **What it does:** Many more tests for the blockchain contract. They try every refusal path, run randomly generated sequences of
  actions, and test the contract with a guardian that needs 2 of 3 people to agree.
- **Why it is useful:** Contract mistakes are hard to undo once deployed.
- **How it was tested:** The contract test suite was run today: 32 tests passed. Full coverage was measured on 23 November and was not
  measured again today.
- **Result: PASS** (test suite).

### 10. Other new items that were not tested live in this retest

| Item | What it is | Result |
|---|---|---|
| Automatic clean-up of old records | Optional; off unless `RETENTION_DAYS` is set | NOT VERIFIED live (automatic tests pass) |
| GitHub automatic checks (CI/CD) | Build and test on GitHub for every change | NOT VERIFIED (the branch has not been pushed) |
| Sepolia deployment scripts and check | Deploying to the public test network | BLOCKED (not configured) |
| Extra security header in the web server | `Permissions-Policy` in nginx | NOT VERIFIED (the Docker setup was not run today) |
| Browser tests, load test, bundle check | Tools for developers | Browser tests passed (19 of 19) on 24 November; the others were not run today |

---

## Problems found and fixed during live testing (24 November 2026)

| # | Problem in simple words | Fixed | Re-tested |
|---|---|---|---|
| A-34 | After a session was ended from another device, that browser could not sign in again; the login page waited forever | Yes | PASS |
| A-35 | With Redis, logging out while other requests were still running sometimes caused an error | Yes | PASS (20 of 20) |
| A-36 | A setting was renamed in the new Spring Boot version, so Redis stored sessions under the wrong name | Yes | PASS |
| A-37 | API keys did not work at all when Redis was on, and created a useless session on every call without it | Yes | PASS |
| A-38 | On a laptop screen the Decision column of the risk table was hidden | Yes | PASS |

Each fix has an automatic test that fails without it. In this retest:
- every live check passed;
- the browser showed only the expected "not signed in" (401) and "refused" (403) answers;
- no server errors appeared.

Still open: the young-wallet effect on the model (A-33), and each server training its own model every night (A-39). Both are harmless
in Shadow mode.

## Screenshots

All are 1920 × 1080, taken from the running application on 24 November 2026, and not edited.

| File | What it shows |
|---|---|
| `01-risk-scoring-assessment.png` | One user's 11 sign-ins with their scores, reasons (Why), model findings (Unusual in) and decisions. The table is scrolled sideways past the Time column to show the model columns; the last column is slightly cut |
| `02-risk-monitoring-refused-sign-ins.png` | Monitoring view: the model card and all refused sign-ins, newest first, with times |
| `03-learned-risk-model-combined-score.png` | Enforce mode: rule score 50 → final 42 (refused), and 100 → 93 (allowed) |
| `04-risk-decision-login-refused.png` | What a user sees when a risky sign-in is refused |
| `05-risk-decision-extra-verification.png` | What a user sees when a risky sign-in needs a code |
| `06-new-device-alert-email.png` | The new-device alert e-mail as it arrived |
| `07-alert-email-setup-confirmed.png` | The alert e-mail setting, confirmed, with the address masked |
| `08-blockchain-transaction-eip1559.png` | A wallet revoked on the blockchain, with its transaction hash; the list shows it as Revoked |

No passwords, keys, tokens or real personal data are visible. The wallet addresses are throwaway test wallets, and the e-mail
addresses use the reserved domain `example.test`.
