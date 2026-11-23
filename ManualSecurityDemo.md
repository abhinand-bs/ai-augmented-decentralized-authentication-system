# BlockAuth — Manual Security Demonstration

A step-by-step guide for showing a reviewer three safe attacks against our **own local** BlockAuth, and
showing that each one is **refused by the server**, **recorded**, and **visible to an administrator**.

Everything here runs on this computer only: the local Hardhat blockchain, the backend, the web app, and
the Neon **test** database. No real users, no real money, no outside systems.

---

## 1. Purpose

A login system is only safe if the **server** refuses bad requests, not just the web page. In this demo
we act as an attacker against our own app and show three things for each attack:

1. **Refused** — the server answers with an error code (what the attacker sees).
2. **Recorded** — the server saves a security event in the database.
3. **Visible** — an administrator sees that event on the **Security** page.

These are three separate facts; we check each one.

## 2. Prerequisites and local setup

- Chrome with **MetaMask**, two accounts:
  - **Admin:** `0x5a32CD633E75D6c647a1B7c070d6B9C0a120395a` (listed in `BLOCKAUTH_ADMIN_WALLETS`).
  - **Normal user:** any other account, e.g. `0xb9a3102D57961A440245f911B156aa23AF209865`.
- **Postman** (or any tool that can send HTTP requests) for attack 2.
- Start the project as in `Running.txt` (three terminals: Hardhat node, deploy + backend on the test
  database, frontend). Open `http://localhost:5173`.
- After a blockchain restart, register both accounts again (**Register** → **Sign & Register**).

> **Important for attack 2:** send those requests to `http://127.0.0.1:8080`, **not** `localhost`.
> The attack blocks the sender's address for 15 minutes. Your browser reaches the backend as `::1`, so
> using `127.0.0.1` in Postman blocks only Postman, and your browser can still log in. A backend restart
> also clears the block (it is kept in memory).

## 3. The three attacks and why we chose them

| # | Attack | Why |
|---|---|---|
| 1 | **Unauthorized admin access** — a normal user calls an admin API directly | Shows that the **server** checks the role on every request; hiding the admin menu is not the protection. |
| 2 | **Repeated failed logins** — 11 fake login attempts in a row | Shows that guessing is stopped: after 10 failures the address is blocked for 15 minutes. |
| 3 | **Replay** — send a real, already used login signature again | Shows that every login message works **only once**, so a copied signature is useless. |

Code behind them (for questions):

| Attack | Endpoint | Where the check is | Event label on the Security page |
|---|---|---|---|
| 1 | `GET /api/admin/users` | `config/SecurityConfig.java` (role rules), `security/RestAccessDeniedHandler.java` | **Privilege escalation attempt** |
| 2 | `POST /api/auth/verify` | `auth/ChallengeService.java`, `auth/AuthFailureRecorder.java`, `security/InMemoryRateLimiter.java` (10 failures in 15 min → 15-min block), `security/RateLimitFilter.java` | **Invalid challenge** (×10), **IP temporarily blocked** |
| 3 | `POST /api/auth/verify` | `auth/AuthenticationChallengeRepository.consume` (marks the message used in one database step) | **Replay attempt** |

All events are saved by `audit/AuditService.java` in the `security_events` and `authentication_events`
tables, and shown on **Admin → Security** (`frontend/src/pages/SecurityMonitoring.jsx`).

---

## 4. Manual steps

### Attack 1 — Unauthorized admin access

1. In MetaMask choose the **normal user** account. In the app click **Login** → **Connect Wallet** →
   **Sign Challenge**, and sign in MetaMask. You land on **Dashboard** (no Admin menu).
2. *(Optional, to explain the difference)* Type `http://localhost:5173/admin` in the address bar. The page
   says **"You do not have permission to view this page."** This is only the web page; the real test is
   next.
3. Now call the admin API itself. Type this in the address bar and press Enter:
   `http://localhost:5173/api/admin/users`
   (The browser sends your user session cookie automatically. No CSRF token is needed for a GET.)
4. **Expected response** (shown as text in the browser):
   ```json
   {"status":403,"code":"ACCESS_DENIED","message":"You do not have permission to perform this action.", ...}
   ```
   It means: you are signed in, but you are not an admin, so the server refuses.
5. *(Optional)* Same with DevTools: press **F12** → **Console**, type
   `fetch('/api/admin/statistics').then(r => r.json()).then(console.log)` → `403 ACCESS_DENIED`.
6. **Normal use still works:** click **Dashboard** or **History**; they load as usual.

Screenshot: the 403 JSON in the browser.

### Attack 3 — Replay of a used login (do this before attack 2)

1. Still as the **normal user**, first **Logout**, then open DevTools (**F12**) → **Network** tab, and tick
   **Preserve log**.
2. Click **Login** → **Sign Challenge**, sign in MetaMask. You are on **Dashboard** again.
3. In the Network list click the request named **verify** (POST, status 200). Open the **Payload** tab →
   **view source**, and copy the whole JSON. It looks like
   `{"walletAddress":"0x…","challenge":"localhost:5173 wants you to sign in …","signature":"0x…"}`.
   This is exactly what an attacker who saw your traffic would copy.
4. Go to the **Console** tab. (If Chrome refuses to paste, type `allow pasting` first.) Paste this, putting
   the copied JSON where it says `PASTE_HERE`, and press Enter:
   ```js
   const body = PASTE_HERE;
   await fetch('/api/auth/csrf');
   const token = decodeURIComponent(document.cookie.match(/XSRF-TOKEN=([^;]+)/)[1]);
   const res = await fetch('/api/auth/verify', { method: 'POST',
     headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token }, body: JSON.stringify(body) });
   console.log(res.status, await res.json());
   ```
   We send a valid CSRF token on purpose, so that **only** the replay protection can stop the request.
5. **Expected response:** `401` with `"code":"CHALLENGE_ALREADY_USED"` — *"This authentication challenge
   has already been used."* The signature is real, but the message was used once already.
6. **Normal use still works:** you are still signed in (click **Dashboard**). Open **History**: the newest
   row is **Login · Failure · Challenge already used** — the user can see the attempt too.

Screenshots: the console output; the History row.

### Attack 2 — Repeated failed logins (do this last)

1. In Postman, create a request **GET** `http://127.0.0.1:8080/api/auth/csrf` and click **Send**. The answer
   has a `"token"` value; Postman also keeps the `XSRF-TOKEN` cookie.
2. Create a request **POST** `http://127.0.0.1:8080/api/auth/verify` with:
   - Headers: `Content-Type: application/json` and `X-XSRF-TOKEN: <the token from step 1>`
   - Body (raw, JSON) — a login message the server never issued, and a fake signature:
     ```json
     {
       "walletAddress": "0xb9a3102D57961A440245f911B156aa23AF209865",
       "challenge": "forged login message",
       "signature": "0x1111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111"
     }
     ```
     (The signature is `0x` followed by exactly 130 hex characters, so it passes the format check and
     reaches the real login check.)
3. Click **Send** 11 times (or run it 11 times with the Collection Runner).
4. **Expected responses:**
   - Sends 1–10: `401`, `"code":"INVALID_CHALLENGE"` — the login message was never issued by the server.
   - Send 11: `429`, `"code":"IP_TEMPORARILY_BLOCKED"`, header **`Retry-After: 900`** (15 minutes).
   - Any login or registration request from Postman now gets the same `429` until the 15 minutes pass.
5. **Normal use still works:** in the browser (a different address), the user can still log in and use
   the app.

Screenshot: Postman showing the 11th response (429 and the Retry-After header).

### Admin verification (after the attacks)

1. In MetaMask switch to the **admin** account. The app signs the user out ("Wallet changed — you have been
   signed out."). Click **Login** → **Sign Challenge** and sign. You land on **Admin dashboard**.
2. Click **Security** (top menu) → tab **Security events**. In the **Wallet** box paste the user's wallet
   address. You see, newest first, with **Time**, **Attack type**, **Wallet**, **IP address**, **Response =
   Blocked**, **Description**:
   - **IP temporarily blocked** — IP `127.0.0.1` (attack 2)
   - **Invalid challenge** ×10 — IP `127.0.0.1` (attack 2)
   - **Replay attempt** — "An already consumed authentication challenge was submitted again." (attack 3)
   - **Privilege escalation attempt** — "… Target: GET /api/admin." (attack 1)

   The **Attack type** dropdown shows one kind at a time.
3. Click the tab **Authentication events** (same Wallet filter): **Login · Blocked · IP temporarily
   blocked**, ten **Login · Failure · Invalid challenge**, and **Login · Failure · Challenge already used**.
   (Attack 1 is not a login, so it appears only under Security events.)
4. Click **Admin** (dashboard): the **Detected and blocked** total and the bars for these attack types have
   gone up. Click **Refresh** to reload them.

Screenshots: Security events (filtered), Authentication events (filtered), Admin dashboard.

---

## 5. Expected and actual responses

The actual results below come from a run on **24 October 2026** of exactly these steps on this
computer. For that run a stand-in wallet signed with public Hardhat test keys, with a Hardhat test
account as admin (set for that run only); with real MetaMask the steps and answers are the same. The
app ran on port 5174/18080 for that run instead of 5173/8080.

| Attack | Expected | Actual |
|---|---|---|
| 1 | `403 ACCESS_DENIED` | `403 ACCESS_DENIED` — `{"status":403,"code":"ACCESS_DENIED","message":"You do not have permission to perform this action.",…,"path":"/api/admin/users"}` |
| 2 | 10 × `401 INVALID_CHALLENGE`, then `429 IP_TEMPORARILY_BLOCKED` | 10 × `401 INVALID_CHALLENGE`; 11th `429 IP_TEMPORARILY_BLOCKED`, `Retry-After: 900`; a following challenge request from the same address also `429`. Another address could still request a challenge (`201`) and its session kept working (`200`). |
| 3 | `401 CHALLENGE_ALREADY_USED` | `401 CHALLENGE_ALREADY_USED`; the same user's session still answered `200`; History showed **Login · Failure · Challenge already used**. |

## 6. Result table

| Test | Attack blocked? | Admin event visible? | Evidence |
|---|---|---|---|
| Unauthorized admin access | **Yes** — 403 ACCESS_DENIED | **Yes** — *Privilege escalation attempt* | `01-attack1-user-calls-admin-api-403.png`, `04-admin-security-events-all-three-attacks.png` |
| Repeated failed authentication | **Yes** — 429 IP_TEMPORARILY_BLOCKED after 10 failures | **Yes** — *Invalid challenge* ×10 + *IP temporarily blocked* | `02-attack2-responses.json`, `04-…png`, `05-admin-authentication-events.png` |
| Challenge replay | **Yes** — 401 CHALLENGE_ALREADY_USED | **Yes** — *Replay attempt* | `03-attack3-replay-response.json`, `03-attack3-replay-in-user-history.png`, `04-…png` |

All three: **Passed** (refused, recorded and displayed, each confirmed in the run above). Evidence files
are in `security-tests/manual-demo/`; `06-admin-dashboard-counters.png` shows the dashboard.

## 7. Screenshot checklist for the live demo

| File name | What it shows |
|---|---|
| `demo-01-admin-api-403.png` | Browser showing the 403 ACCESS_DENIED JSON (attack 1) |
| `demo-02-replay-401.png` | DevTools console with `401` and `CHALLENGE_ALREADY_USED` (attack 3) |
| `demo-03-user-history-replay.png` | User's History with *Challenge already used* (attack 3) |
| `demo-04-postman-429.png` | Postman, 11th response: 429 IP_TEMPORARILY_BLOCKED and Retry-After (attack 2) |
| `demo-05-admin-security-events.png` | Admin → Security → Security events, filtered by the user wallet |
| `demo-06-admin-auth-events.png` | Admin → Security → Authentication events, filtered by the user wallet |
| `demo-07-admin-dashboard.png` | Admin dashboard counters |

## 8. Gaps found

- **No missing protection and no missing admin visibility** for these three attacks: each was refused by
  the server, saved in the database and shown on the Security page.
- **Note, not a gap:** opening the admin *page* (`/admin`) as a user is stopped by the web page itself and
  makes no server request, so it creates no event. The server-side protection is shown by calling the admin
  **API** (attack 1), which is refused and recorded.
- **Limits to mention honestly:** the 15-minute block and the request counters live in the server's
  memory, so a backend restart clears them; and an attacker with many different addresses is not slowed by
  a per-address limit. Three passing tests do not prove the whole system is secure.

## 9. What to say to the reviewer

- **Attack 1:** "I'm logged in as a normal user and I call an admin address directly, skipping the
  menu. The server checks my role itself and answers 403. And here, as admin, I can see the attempt, with
  the time, the wallet and what was targeted."
- **Attack 2:** "This is someone trying to guess their way in. Each try is refused, and after ten failures
  the server blocks that address for fifteen minutes; the eleventh answer is 429 with a fifteen-minute wait.
  My normal browser is a different address and still works. The admin sees every failed try and the block."
- **Attack 3:** "I copied a real, valid login from the network tab, like an attacker watching the traffic,
  and sent it again. The signature is genuine, but every login message works only once, so the server says
  'already used'. The attempt shows up for the admin as a replay attempt, and even in the user's own
  history."
