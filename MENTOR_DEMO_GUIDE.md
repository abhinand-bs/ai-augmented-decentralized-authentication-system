# BlockAuth: Mentor Demo Guide

This guide shows you how to start BlockAuth in the Antigravity terminal and how to present it to your mentor, step by step. Everything
here comes from the current code (branch `feature/risk-scoring-hardening`, 24 November 2026). Section 11 lists what was checked and what
you should practise once yourself.

**Demo length:** about 25 minutes, plus questions. Steps marked *(optional)* can be skipped if time is short.

---

## 1. BlockAuth in 30 seconds

BlockAuth is a login system **without passwords**:

- You prove who you are by **signing a one-time message with your crypto wallet** (MetaMask).
- The list of registered and revoked wallets is kept on a **blockchain smart contract**, so the database alone cannot be changed to let
  someone in.
- After the signature is checked, a **risk score** decides whether the sign-in is allowed, needs an extra code, or is refused.
- Users can turn on an **authenticator-app code** (the second step) and **e-mail alerts** for new devices.
- Administrators can **revoke wallets on the blockchain**, watch **security events** and **sign-in risk**, and use an **emergency stop**.

| Part | Technology | Folder |
|---|---|---|
| Website | React 19 + Vite | `frontend/` |
| Server (API) | Java 21, Spring Boot 4, Spring Security | `backend/` |
| Database | PostgreSQL on Neon (cloud), tables created by Flyway | `backend/src/main/resources/db/migration/` |
| Blockchain | Solidity smart contract `WalletRegistry`, local Hardhat chain | `contracts/` |
| Server ↔ chain | Web3j | `backend/.../blockchain/` |
| Wallet | MetaMask in Chrome | — |
| E-mail (demo) | Mailpit, a local test inbox in Docker | `docker-compose.yml` |

---

## 2. Prepare the day before

### 2.1 Check the tools

Open a terminal in Antigravity: menu **Terminal → New Terminal**. It must be **PowerShell**. If it shows something else, choose
PowerShell from the terminal's drop-down next to the **+** button. Then run:

```powershell
java -version
```

```powershell
node -v
```

```powershell
docker --version
```

| Tool | You need | Why |
|---|---|---|
| Java (JDK) | **21** | Runs the backend. Maven is not needed: the project has its own `mvnw.cmd` |
| Node.js and npm | 22 or newer | Runs the website and the blockchain node |
| Docker Desktop | running | Only for the e-mail demo (Mailpit) |
| Chrome + **MetaMask** | latest | Signs the login messages |
| Authenticator app on your phone | Google or Microsoft Authenticator, or similar | Only for the second-step demo |
| Neon console in Chrome | logged in | To reset the demo data (section 3.3) and to show the tables |

### 2.2 MetaMask accounts

Create **three accounts** in MetaMask:

1. **Admin account**: its address must be in `BLOCKAUTH_ADMIN_WALLETS` in `C:\BlockAuth\.env.local`.
2. **User account**: a normal user.
3. **Stranger account**: never registered. It shows that unknown wallets are refused.

The accounts need **no ETH and no special network**. Users only sign messages; the server sends all blockchain transactions with its own
operator key.

**A second Chrome profile for the "new device" demos.** The server recognises a browser by a device cookie, and every account in one
browser shares that browser's device. To show a sign-in from a new device without disturbing your main window:

1. In Chrome, click the profile icon (top right) → **Add** → create a profile called "Second device".
2. In that profile, install MetaMask and choose **Import an existing wallet** with the same Secret Recovery Phrase, so the **User**
   account is there too. (Never show the recovery phrase on the projector.)

The main window is the user's usual device; the second profile is the new phone or laptop.

### 2.3 The settings file `.env.local`

The file `C:\BlockAuth\.env.local` already exists on this laptop.

> **Never open `.env.local` on the projector.** It holds passwords and private keys.

It must contain these names (fill them in from `.env.example` if one is missing):

| Setting | What it is |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | The main Neon database (**not used in the demo**, see the note below) |
| `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD` | The Neon **test** branch, used for the demo |
| `CONTRACT_ADDRESS` | `0x5FbDB2315678afecb367f032d93F642f64180aa3`: the address of the first contract deployed on a fresh Hardhat chain |
| `BLOCKCHAIN_OPERATOR_PRIVATE_KEY` | Hardhat's public test key of Account #0 |
| `BLOCKAUTH_ADMIN_WALLETS` | Your **Admin** MetaMask address |
| `APP_ORIGIN` | `http://localhost:5173` |
| `SESSION_COOKIE_SECURE` | `false` (the demo runs on plain `http://localhost`) |
| `SECOND_STEP_ENCRYPTION_KEY` | 32 random bytes in Base64 |

> **Why the demo uses the test database.** The newest features added two database changes (migrations V4 and V5). They are already on
> the Neon **test** branch, but not yet on the main branch. The start command in section 3.4 points the backend at the test branch, for
> that terminal only. If you ever start the backend on the main branch, take a **Neon snapshot first**: the backend will apply V4 and V5
> there.

### 2.4 Install the packages (once)

```powershell
cd C:\BlockAuth\contracts
```

```powershell
npm install
```

```powershell
cd C:\BlockAuth\frontend
```

```powershell
npm install
```

The backend downloads its libraries by itself the first time it starts.

### 2.5 Practise once

Do the whole of section 4 once, alone. Then you know how long each step takes and what MetaMask asks.

---

## 3. Start the project (30 minutes before the demo)

You need **four terminals**. In Antigravity, click **+** in the terminal panel for each new one. Start them in this order.

> If another BlockAuth window is still running, close it first. Ports **8545, 8080 and 5173** must be free (section 10 shows how to
> free them).

### 3.1 Terminal 1: the blockchain (leave it running)

```powershell
cd C:\BlockAuth\contracts
```

```powershell
npx hardhat node
```

You will see `Started HTTP and WebSocket JSON-RPC server at http://127.0.0.1:8545/` and a list of test accounts. Keep this terminal
visible: every blockchain transaction appears here during the demo.

### 3.2 Terminal 2: deploy the contract, start the mail inbox

```powershell
cd C:\BlockAuth\contracts
```

For the demo, a reactivation waits **1 minute instead of 10**, so you can show it working live:

```powershell
$env:REGISTRY_DELAY_SECONDS = "60"
```

```powershell
npx hardhat run scripts/deploy.js --network localhost
```

It must print `WalletRegistry deployed to 0x5FbDB2315678afecb367f032d93F642f64180aa3 …`.

> **Deploy only once after each fresh start of Terminal 1.** A second deploy gets a different address, which would no longer match
> `.env.local`.

Now start the local mail inbox. Docker Desktop must be running.

```powershell
cd C:\BlockAuth
```

```powershell
docker compose --profile mail up -d mailpit
```

Open **http://127.0.0.1:8025** in Chrome: this is the Mailpit inbox where the demo e-mails will arrive.

### 3.3 Reset the demo data (needed after every fresh blockchain start)

A fresh Hardhat chain is empty, but the test database still remembers old registrations. Empty the test database so both start clean:

1. Open the **Neon console** → your project → **SQL Editor**.
2. In the branch selector, choose the branch called **test**. **Do not choose the main branch.**
3. Run:

```sql
TRUNCATE users, devices RESTART IDENTITY CASCADE;
```

This deletes every user, session, device, event, risk assessment, flag, API key and alert e-mail **on the test branch**. It keeps the
trained risk model and the emergency-stop setting. (This command was checked on the test branch inside a transaction that was then
cancelled.)

> Do not run the backend's integration tests (`mvnw verify`) on the demo day: they also use the test branch.

### 3.4 Terminal 3: the backend

```powershell
cd C:\BlockAuth\backend
```

This line points the backend at the **test** database, for this terminal only:

```powershell
Get-Content ..\.env.local | Where-Object { $_ -match '^TEST_DB_(URL|USERNAME|PASSWORD)=(.*)$' } | ForEach-Object { Set-Item "env:DB_$($Matches[1])" $Matches[2] }
```

The backend's waiting time must match the 1-minute delay of the contract:

```powershell
$env:ADMIN_ACTION_DELAY = "1m"
```

Turn on e-mail alerts and send them to Mailpit. These settings are for the local inbox only; a real mail server needs encryption:

```powershell
$env:MAIL_ENABLED = "true"; $env:MAIL_HOST = "127.0.0.1"; $env:MAIL_PORT = "1025"; $env:MAIL_SMTP_AUTH = "false"; $env:MAIL_STARTTLS_REQUIRED = "false"; $env:MAIL_FROM = "alerts@blockauth.local"
```

```powershell
.\mvnw.cmd spring-boot:run
```

Wait for these two lines:

- `Blockchain verified: chain 31337, WalletRegistry deployed, operator matches, … delay PT1M`
- `Started BlockAuthApplication in … seconds`

If the backend stops with a blockchain error, see section 10.

### 3.5 Terminal 4: the website

```powershell
cd C:\BlockAuth\frontend
```

```powershell
npm run dev
```

It prints `Local: http://localhost:5173/`.

### 3.6 Last checks (5 minutes before)

- [ ] **http://localhost:8080/actuator/health** shows `{"status":"UP"}`.
- [ ] **http://localhost:5173** shows "Sign in with your wallet, not a password."
- [ ] **http://127.0.0.1:8025** shows the Mailpit inbox.
- [ ] MetaMask is unlocked.
- [ ] Chrome is zoomed to 110–125%, notifications are off, and `.env.local` is closed.
- [ ] Nothing is registered yet: your mentor should see everything happen.

---

## 4. The demo, step by step

Each step says **what to do**, **what you should see**, and **what to say**. MetaMask opens a small window for every signature: click
**Sign** (or **Confirm**).

### Step 1. The home page (1 minute)

- **Do:** Open http://localhost:5173. Scroll to **"How a sign-in is decided"**.
- **See:** "Sign in with your wallet, not a password."
- **Say:** "BlockAuth has no passwords. You prove you own a wallet by signing a one-time message. The blockchain decides which wallets
  are registered, and a risk score checks whether the sign-in looks normal."

### Step 2. An unknown wallet is refused (1 minute)

- **Do:** In MetaMask, switch to the **Stranger** account. Click **Login** → **Connect Wallet** → **Sign Challenge** → sign in MetaMask.
  Do this **only once** here.
- **See:** "This wallet is not registered."
- **Say:** "The signature is real, but this wallet is not in the blockchain registry, so it is refused. The attempt is recorded as a
  security event."

### Step 3. Register the administrator (2 minutes)

- **Do:** Switch MetaMask to the **Admin** account. Click **Register** → **Connect Wallet** → **Sign & Register** → sign.
- **See:** "Registration complete." Open **Registration details**: the wallet, the role **ADMIN**, and the **Transaction** hash with a
  **Copy** button. Terminal 1 shows the transaction.
- **Do:** Click **Log in with this wallet** → **Sign Challenge** → sign.
- **See:** The **Admin dashboard**, and the admin menu (**Admin**, **Wallets**, **Security**, **Controls**).
- **Say:** "The server wrote this wallet into the smart contract; that is the transaction you see in the terminal. The wallet got the
  admin role because its address is on the server's admin list."
- **Do:** Click **Logout**.

### Step 4. Register and log in as a normal user (2 minutes)

- **Do:** Switch MetaMask to the **User** account (switching accounts also signs you out). **Register** → **Connect Wallet** →
  **Sign & Register** → sign. Then **Log in with this wallet** → **Sign Challenge** → sign.
- **See:** **Dashboard**: wallet address, Session "Authenticated by wallet signature", Account status, Role USER, Registered date.
- **Try:** Click **Sign Challenge** quickly three times on another login. Only one request is sent.
- **Say:** "Each message works once and expires after two minutes, so a copied signature cannot be used again. This first sign-in got
  a trust score of 100, because there is nothing unusual yet. I will show the score later on the admin screen."

### Step 5. History and wallet status (1 minute)

- **Do:** Click **History**, then **Wallet status**.
- **See:**
  - History: your own events (registration, login), with an **Event** filter.
  - Wallet status: the status **read directly from the smart contract**, for example Active.
- **Say:** "The wallet status comes from the blockchain, not from our database. The blockchain is the final word."

### Step 6. Account security: sessions and devices (1 minute)

- **Do:** Click **Account security**.
- **See:**
  - **Signed-in sessions**, with "This device" marked.
  - **Devices**.
  - **Second step** ("Set up the second step").
  - **Alert e-mail**.
- **Say:** "Every browser gets a random device cookie. The server uses it to know whether a sign-in comes from a device the wallet has
  used before."

### Step 7. The e-mail alert feature: set it up (2 minutes)

- **Do:** Under **Alert e-mail**, type an address such as `demo-user@example.test` → **Send a confirmation code** → sign in MetaMask.
- **Do:** In Mailpit (http://127.0.0.1:8025), open **"BlockAuth: confirm your alert e-mail address"** and copy the 6-digit code.
- **Do:** Back in BlockAuth, put it in **Code from the e-mail** → **Confirm address**.
- **See:** The address is shown masked (for example `d***@example.test`), with the badge **Confirmed**.
- **Say:** "E-mail alerts are optional. The address must be confirmed with a code, so nobody can send alerts to someone else's inbox.
  The address is stored encrypted, and the page only shows it masked."

### Step 8. A new device: the risk score drops, an e-mail arrives (3 minutes)

The **second Chrome profile** (section 2.2) plays the role of a new phone or laptop. The main window stays the user's usual device.

- **Do:** Open the second profile's Chrome window → http://localhost:5173/login → **Connect Wallet** (choose the **User** account) →
  **Sign Challenge** → sign.
- **See:** The login is **allowed** (trust score 70: new device −30).
- **See in Mailpit:** **"BlockAuth: your wallet signed in on a new device"**, showing:
  - the device (for example "Chrome on Windows");
  - the time;
  - the network with the last part hidden. On this laptop it is a local address: `127.0.0.x`, or `0:0:0::/48` if the computer uses
    the newer IPv6 form;
  - "If this was not you, open BlockAuth and choose This was not me."
- **See in the main window:** **Account security** (refresh the page) now shows **"New sign-ins to check"**, with **This was me** and
  **This was not me**. It is shown to the user's other sessions, not to the new device itself.
- **Say:** "This sign-in came from a device the wallet never used. The score dropped from 100 to 70: still allowed, but the owner is
  told on screen and by e-mail. If it was not them, 'This was not me' signs that device out. The e-mail contains no links, so it cannot
  be copied for phishing."
- **Do:** Click **This was me**. In the second window, click **Logout**.

### Step 9. A risky sign-in is refused (2 minutes)

- **Do (main window):** Logout. Switch MetaMask to **Stranger** and try **Login** **two more times** (with Step 2, that makes 3
  failures from this computer). Each time: "This wallet is not registered." Then switch back to **User**.
- **Do (second window):** Make it a brand-new device again:
  1. Press **F12** → **Application** → **Storage → Cookies → http://localhost:5173**.
  2. Click **BLOCKAUTH_DEVICE** → **Delete**. Close DevTools.
  3. **Login** → **Sign Challenge** → sign with **User**.
- **See:** "This sign-in was refused because it does not look like your usual sign-ins."
- **Say:** "Three failed attempts came from this address in the last hour, so the system takes off 25 more points. New device −30 and
  repeated failures −25 give 45. That is below 50, and this user has no second step, so the sign-in is refused, even though the
  signature is correct."

### Step 10. The second step: the same risky sign-in continues with a code (3 minutes)

- **Do (main window):** **Login** as **User**. This is the usual device, so 100 − 25 = 75: allowed.
- **Do:** **Account security** → **Set up the second step**. In your phone's authenticator app, add an account by typing the **Key**
  shown (there is no QR code; the key is typed in). Enter the 6-digit code in **Code shown in your app** → **Turn on** → sign in
  MetaMask.
- **See:** **10 backup codes**, shown once. The badge **On**, and "10 of 10 backup codes left".
- **Say:** "This browser is now trusted for 30 days. Other browsers must enter a code."
- **Do (second window):** Delete the **BLOCKAUTH_DEVICE** cookie again (as in Step 9) → **Login** → sign with **User**.
- **See:** "Enter your verification code". Type the current code from the app (wait for a new code if you used the last one a moment
  ago) → **Continue** → **Dashboard**.
- **Optional:** type `000000` first: "That code is not right, or it was already used."
- **Say:** "The same risky sign-in that was refused a minute ago now continues with a code from my phone. Below a score of 90, a user
  with the second step must enter the code. Without it, scores from 50 to 89 are allowed and below 50 are refused."
- **Do:** Close the second window. Use the main window from now on.

> **Keep the main window's device cookie. Never delete it in the main window during the demo.** All MetaMask accounts in one browser
> share that browser's device. After the failures in Step 9, a new device would cost the admin 30 + 25 points, which is a refusal.
>
> **Failure budget:** Step 2, Step 9, the optional wrong code, Step 14 and the optional Step 18 together make about 7 failures.
> Stay below 10 in 15 minutes.

### Step 11. The admin view of risk scoring (3 minutes)

- **Do:** Switch MetaMask to **Admin** (this signs the user out) → **Login** → sign. This is the usual device: 100 − 25 = 75, allowed.
  Then **Security** → tab **Sign-in risk**.
- **See:**
  - The **Anomaly model** card: Mode **Shadow**, the Version, when it was Trained, and its Training data "2000 synthetic sign-ins (cold
    start)".
  - Filters: **Decision**, **Anomaly at least**, **Wallet**. Paste the User's address into **Wallet**.
  - One row per sign-in, with these columns:
    - **Rules**: the score from the rules;
    - **Anomaly**: the model's number, 0 to 1;
    - **Final**: the score that counted;
    - **Decision**: Allow, Second step or Refuse;
    - **Why**: the rules that fired;
    - **Unusual in**: what the model found unusual;
    - **Model**: the version and mode.
  - Your demo's rows: 100 Allow, 70 Allow (New device), 45 Refuse (New device, Repeated failures), and the second-step sign-ins.
- **Say:** Use the risk explanation in section 5.4.
- **Do:** Tab **Security events**: "Unregistered wallet", "Low trust login", and so on. Tab **Authentication events**: "Alert email
  sent".

### Step 12. Admin dashboard (1 minute)

- **Do:** Click **Admin**.
- **See:** Counters for Users, Active wallets, Revoked wallets, Authentication attempts (Successful / Failed), Why logins failed, and
  Security events by type.
- **Say:** "This is the overview. Every number comes from the audit tables in the database."

### Step 13. Revoke the user on the blockchain (2 minutes)

- **Do:** **Wallets** → paste the User's address into **Check any wallet on the blockchain** → **Check on-chain**. In **On-chain
  status** click **Revoke** → **Revoke wallet** → sign in MetaMask.
- **See:** "Wallet 0x… is now revoked." Open **Transaction details** for the transaction hash. Terminal 1 shows the transaction. In
  **Registered users** the status becomes **Revoked**. The list refreshes a moment later; the old rows look faded until it does.
- **Say:** "Every admin change needs a fresh signature from the admin's wallet, not just the session. The revocation is written to the
  smart contract at once."

### Step 14. The revoked user cannot log in (1 minute)

- **Do:** Switch MetaMask to **User** → **Login** → sign.
- **See:** "This wallet has been revoked by an administrator."
- **Say:** "The login checks the blockchain, so a revoked wallet is refused even if someone edited our database."

### Step 15. Reactivation must wait (2 minutes)

- **Do:** Switch to **Admin** → login → **Wallets** → check the User's address → **Activate** → **Request reactivation** → sign.
- **See:**
  - "Reactivation of … is requested".
  - **Controls → Waiting actions** shows **Activate wallet** with a time, and a **Cancel** button.
  - After about 1 minute the status becomes Executed. The user can log in again (with a code, because the second step is on and the
    repeated failures keep the score below 90).
- **Say:** "Giving access back is slow on purpose. If an admin account is stolen, the attacker cannot instantly unlock wallets; another
  admin can cancel the request in the meantime. In real use the wait is 10 minutes."

### Step 16. Suspicious wallets *(optional, 2 minutes)*

- **Do:** **Controls → Suspicious wallets** → **Wallet**: the User's address, **Reason**: "Demo" → **Flag wallet** → sign.
- **Say:** "A flag takes 35 points off this wallet's sign-ins. It also takes 35 off any wallet that used the same device, because one
  person may control both."
- **Do:** Click **Remove flag** afterwards, **before you log out**.

> All accounts in the main window share one device. While the User is flagged, the admin also counts as "linked to a flagged wallet"
> (−35). Together with the repeated failures (−25) that would make the admin's next login a refusal (40). So remove the flag while the
> admin is still logged in.

### Step 17. API keys *(optional, 2 minutes)*

- **Do:** **Controls → API keys**: **Name** "report", tick **audit:read**, **Valid for (days)** 1 → **Create key** → sign. Copy the key
  shown (it is shown only once).
- **Do** (in a fifth terminal; put your key in place of `<key>`):

```powershell
curl.exe -H "X-API-Key: <key>" http://localhost:8080/api/admin/statistics
```

- **See:** The statistics as JSON. The same key on `/api/admin/users` gives 403 (wrong scope).
- **Do:** Click **Revoke** → **Revoke key** → sign. The same `curl.exe` now gives 401.
- **Say:** "Monitoring scripts use read-only keys with limited scopes. Only a hash of the key is stored."

### Step 18. Emergency stop *(optional, 2 minutes)*

- **Do:** **Controls → Emergency stop** → **Reason**: "Demo" → **Switch on the emergency stop** → sign.
- **See:** "The emergency stop is on". A user login now shows "This is paused by an administrator."
- **Do:** **Request to lift it** → sign. It takes effect after the 1-minute delay.
- **Say:** "In an emergency the admin pauses BlockAuth and the smart contract at once. Lifting it waits, like every action that gives
  access back."

### Step 19. The technical side (3 minutes)

- **Terminal 1:** the blockchain transactions from registration and revocation.
- **Neon console** (test branch) → **Tables**:
  - `users`: wallets and roles;
  - `authentication_events`: every login;
  - `security_events`: blocked attacks;
  - `login_assessments`: one row per scored sign-in, with `rule_score`, `anomaly_score`, `score`, `rules`, `decision`;
  - `risk_models`: the trained models;
  - `alert_emails`: the address is stored encrypted (unreadable text).
- **Code to show in Antigravity:**

| File | What it shows |
|---|---|
| `contracts/contracts/WalletRegistry.sol` | The smart contract: register, revoke, the delayed reactivation, pause |
| `backend/src/main/java/com/blockauth/risk/TrustPolicy.java` | The risk rules, the points and the decision |
| `backend/src/main/java/com/blockauth/risk/anomaly/IsolationForest.java` | The learned model, written in plain Java |
| `backend/src/main/java/com/blockauth/alert/AlertMailer.java` | How the e-mails are written and sent |
| `frontend/src/pages/SecurityMonitoring.jsx` | The admin risk screen |

### Step 20. Finish (1 minute)

- **Say:** "To sum up: no passwords, wallet signatures as proof, the blockchain as the source of truth for who is registered or
  revoked, a risk score with rules and a learned model, an optional second step, e-mail alerts for new devices, and admin controls that
  need a fresh signature and make dangerous actions wait."

---

## 5. Risk scoring, explained

### 5.1 What it is

After the wallet signature is checked and correct, BlockAuth gives the sign-in a **trust score from 0 to 100**. 100 means nothing is
unusual. The score decides what happens next.

### 5.2 How the score is calculated (the rules)

Start at **100** and take off points:

| What the server checks | Points off | How it knows |
|---|---|---|
| New device | −30 | The browser's device cookie was never used by this wallet. With the second step on, a device counts as known only while it is trusted (30 days after a code was entered there) |
| New network | −20 | The internet address was never used by this wallet. In a local demo everything comes from this computer, so this rule does not fire here |
| Flagged wallet | −35 | An admin flagged the wallet as suspicious |
| Repeated failures | −25 | 3 or more failed attempts from the same address in the last hour |
| Linked to a flagged wallet | −35 | The wallet used a device that a flagged wallet also used |
| First sign-in ever | none | There is no history to compare with |

These rules are in `backend/src/main/java/com/blockauth/risk/TrustPolicy.java`. The points are settings (`TRUST_*` in `.env.example`).

### 5.3 The decision

| Final score | Wallet **without** the second step | Wallet **with** the second step |
|---|---|---|
| 90–100 | Allow | Allow |
| 50–89 | Allow | Ask for the code |
| 0–49 | **Refuse** | Ask for the code |

If the risk check itself fails (for example the database cannot be reached), the sign-in is **not** let through.

**Examples to tell your mentor:**

- **Same laptop as always:** 100 → allowed.
- **New phone:** 100 − 30 = **70** → allowed, and the owner gets an alert. With the second step: a code is asked.
- **Attacker after failed attempts, on a new device:** 100 − 30 − 25 = **45** → refused. With the second step: a code is asked, which
  the attacker does not have.
- **Flagged wallet on a new device:** 100 − 30 − 35 = **35** → refused.

### 5.4 The learned model (new): what to say

Next to the rules there is a small **machine-learning model**, an **Isolation Forest**. It is written in plain Java inside the project
(`risk/anomaly/IsolationForest.java`), with no outside AI service.

- **What it learns:** what ordinary sign-ins look like. It uses 15 numbers per sign-in, for example:
  - the time of day and the day of the week;
  - the wallet's age;
  - the time since the last sign-in;
  - sign-ins in the last 24 hours;
  - different networks and devices used recently;
  - new device, new network, recent failures;
  - whether the browser changed.

  It never uses the wallet address or any personal data.
- **What it gives:** an **anomaly score from 0 to 1** (about 0.5 = ordinary, near 1 = very unusual), plus the **two facts** that made
  the sign-in unusual. They are shown as "Unusual in", for example "New device, Wallet age days". "Hour cos" and "Hour sin" mean the
  time of day.
- **How it is trained:**
  - Until 200 real sign-ins exist, it is trained on **2,000 made-up normal sign-ins** (that is why the card says "synthetic, cold
    start").
  - After that, it is trained on real allowed sign-ins, at most 50 per wallet, and never from flagged wallets, so one person cannot
    teach it bad habits.
  - It is retrained every night.
- **How it works with the rules: three modes.**

| Mode | What happens |
|---|---|
| Off | Only the rules |
| **Shadow** (the default; what you show) | The model's score is saved and shown to admins, but **never changes a decision** |
| Enforce | An unusual sign-in can lose **up to 30 more points**: penalty = 30 × (anomaly − threshold) ÷ (1 − threshold), only above the threshold (default 0.6). The model can **never add points** |

- **Safe by design:** if the model is missing or fails, the rules decide alone.
- **Honest limitation (say this):** "The model has only been measured on made-up data, so we do not claim real-world accuracy. That
  is why it runs in shadow mode. It is a risk indicator, not proof of identity."

**Enforce mode** was tested on 24 November 2026: a new-device + new-network sign-in went from 50 to 42 and was refused. If your mentor
asks to see it, show `BlockAuth-Feature-Screenshots/03-learned-risk-model-combined-score.png`. Switching it on live needs a backend
restart with `$env:ANOMALY_MODE = "enforce"`, which is not recommended during the demo.

### 5.5 Where results can be seen

| Who | Where | What |
|---|---|---|
| The user | Login page | Goes in, or "Enter your verification code", or "This sign-in was refused because it does not look like your usual sign-ins." The user never sees the number |
| The user | Account security | "New sign-ins to check" for new devices |
| The admin | Security → **Sign-in risk** | Every score, the model's number, the final score, the decision and the reasons |
| The admin | Security → Security events | "Low trust login" for refused sign-ins |
| The database | `login_assessments` table | The same values, one row per sign-in |

---

## 6. The e-mail alert feature (recently added)

**What it does:** When the wallet signs in on a **device it has not used before** (and it already has another device), BlockAuth
e-mails the owner.

**How it works:**

1. **Set up (Account security → Alert e-mail).** The user enters an address. MetaMask asks for a signature that names the address,
   which proves the wallet owner wants it. A **6-digit code** is mailed to that address.
2. **Confirm.** The code is valid for **15 minutes**, with at most 5 tries. After confirming, the badge says **Confirmed** and the
   address is shown masked.
3. **Alert.** At a sign-in on a new device, the server sends **"BlockAuth: your wallet signed in on a new device"**. It contains:
   - the device name;
   - the time (UTC);
   - the network with the last part hidden (for example `198.51.100.x`);
   - the advice to open BlockAuth and choose "This was not me".

   It contains **no links**.
4. **Safety details:**
   - the address is **stored encrypted**;
   - at most **3 alerts per hour** are sent;
   - mail is sent in the background, so a slow mail server never delays or blocks a login;
   - changing the address also notifies the old address ("your alert e-mail address was changed");
   - **Stop e-mail alerts** turns it off.
5. **Off by default.** It needs `MAIL_ENABLED=true` and a mail server. In the demo, the mail server is **Mailpit** (section 3.2), a
   local test inbox, so nothing leaves the laptop.

**How to demo it:** Steps 7 and 8.

**What to say:** "The alert tells the owner quickly when their wallet is used somewhere new. It has no links on purpose, so attackers
cannot copy it for phishing; the user opens BlockAuth themselves and clicks 'This was not me', which signs that device out."

---

## 7. All features at a glance

| Feature | Where | What to click / type | What you should see | What to say |
|---|---|---|---|---|
| Passwordless login | Login | Connect Wallet → Sign Challenge | Dashboard | "A one-time message signed by the wallet, no password" |
| Unknown wallet refused | Login (Stranger) | Sign Challenge | "This wallet is not registered." | "Only wallets in the blockchain registry" |
| Registration on the blockchain | Register | Sign & Register | "Registration complete." + transaction hash | "The server writes the wallet into the smart contract" |
| Admin role | Register with the admin wallet | — | Admin menu | "Given by the server's admin list at registration" |
| One-time, expiring challenge | Login | — | (2-minute expiry, one use) | "A copied signature cannot be used again" |
| History | History | Event filter | Own events | "Every login is audited" |
| Wallet status from the chain | Wallet status | — | Active / Revoked | "Read directly from the contract" |
| Sessions and devices | Account security | Sign out / Sign out everywhere else / Forget | Lists update | "You control where you are signed in" |
| New-device notice | Account security (other session) | This was me / This was not me | Alert disappears / device signed out | "The owner checks unusual sign-ins" |
| Risk score and decisions | Login + admin Sign-in risk | — | Allow / code / refuse | Section 5 |
| Learned model (shadow) | Security → Sign-in risk | — | Anomaly, Unusual in, model card | Section 5.4 |
| Second step (authenticator) | Account security | Set up → Turn on → code | Backup codes; code asked on new devices | "A second proof for risky sign-ins" |
| E-mail alerts | Account security + Mailpit | Send a confirmation code → Confirm | Mail in Mailpit | Section 6 |
| Account switch signs out | MetaMask | Switch account | Logged out | "A session belongs to one wallet" |
| Admin dashboard | Admin | Refresh | Counters | "Overview from the audit tables" |
| Security monitoring | Security | Tabs and filters | Attacks, events, risk | "Everything is recorded" |
| Revoke on chain | Wallets | Check on-chain → Revoke → sign | "is now revoked" + transaction | "Written to the contract at once" |
| Delayed reactivation | Wallets / Controls | Activate → Request reactivation; Cancel | Waiting, then Executed | "Restoring access waits" |
| Suspicious-wallet flags | Controls | Flag wallet / Remove flag | Flag listed | "−35, also for wallets on the same device" |
| Reset a user's second step | Controls | Wallet → Request reset | Waiting action | "For a user who lost the phone and the backup codes; it waits too" |
| API keys | Controls + `curl.exe` | Create key → use → Revoke | 200 / 403 / 401 | "Read-only, scoped, only a hash stored" |
| Emergency stop | Controls | Switch on / Request to lift it | Logins paused | "Also pauses the smart contract" |
| Fresh signature for admin actions | Every admin change | Sign in MetaMask | — | "A stolen session alone cannot change anything" |

**Not working in this demo, do not claim:**

- **Sepolia (public test network):** the scripts exist but no Sepolia address or key is configured, so nothing is deployed there.
- **GitHub CI/CD:** the workflow files exist but have not run on GitHub yet.
- **Several servers with Redis:** this works and was tested, but this demo runs one server without Redis.
- **The "New network" rule:** it cannot fire in a one-computer demo.

---

## 8. Speaking points for ma'am (easy English)

1. "My project is BlockAuth. It is a login system where you do not need a password. You sign in with your crypto wallet."
2. "To log in, the website gives a one-time message. My wallet signs it. The server checks the signature. The message works only once
   and only for two minutes."
3. "The list of registered and revoked wallets is on a blockchain smart contract, so nobody can unlock a wallet just by changing the
   database."
4. "After the signature, the system calculates a trust score. A new device takes off 30 points, failed attempts 25, a flagged wallet
   35. A high score is allowed; a low score is refused or needs a code."
5. "We also added a small machine-learning model, an Isolation Forest. It learns what normal sign-ins look like and says how unusual a
   new one is. For now it only watches, in shadow mode, because it has been trained on made-up data. Admins can see its opinion next to
   the rules."
6. "Users can turn on a second step with an authenticator app. Then a risky sign-in can still continue, but only with the code from
   the phone."
7. "Users can also add an e-mail address. If their wallet signs in on a new device, they get an e-mail. The e-mail has no links, to
   avoid phishing."
8. "Admins can revoke a wallet on the blockchain, flag suspicious wallets, create read-only API keys, and switch on an emergency stop.
   Every admin action needs a new signature, and actions that give access back must wait."
9. "Everything is recorded: logins, attacks, risk scores. The admin can see it all on the Security page."
10. "We tested it with automatic tests: more than 400 backend tests, the contract tests and frontend tests. We also ran a live test of
    the whole system with screenshots."

### Questions ma'am might ask

| Question | Answer |
|---|---|
| What if someone steals my wallet's secret key? | "They can sign. That is why we have the risk score, the second step, new-device e-mails and admin revocation." |
| Why use a blockchain? | "So the list of valid wallets cannot be changed by someone with database access only. Revocations and reactivations are recorded on the chain." |
| Is it AI? | "There is one small unsupervised model, an Isolation Forest, written in plain Java. It runs in shadow mode by default and only gives a risk indicator; the rules make the decision." |
| How accurate is the model? | "We measured it only on made-up data, so we do not claim real-world accuracy. That is why it does not change decisions by default." |
| Does the user pay gas? | "No. The user only signs messages. The server's operator key sends the transactions." |
| What if the e-mail server is down? | "The login still works. E-mails are sent in the background and never block a login." |
| What if the risk check fails? | "Then the sign-in is not let through. We never allow a login because a check failed." |
| Where is the data stored? | "In PostgreSQL on Neon. The alert e-mail address and the authenticator secret are stored encrypted." |

---

## 9. Recovery during the demo

| Problem | What to do |
|---|---|
| MetaMask shows nothing after a click | Open the MetaMask icon: a request is waiting there |
| "This wallet is not registered" for a wallet you registered | The chain was restarted. Deploy again (3.2), reset the test data (3.3), restart the backend (3.4), register again |
| A login is refused that you did not expect | Repeated failures from this computer in the last hour (−25), a new device (−30), or a flagged wallet on the same browser (−35). Use the main window (the known device), remove flags, or use the second step. If the admin is locked out, wait an hour, or reset the data (3.3) and start again |
| 429 "Too many failed attempts" | 10 failures in 15 minutes from this computer. Restart the backend (Terminal 3: **Ctrl+C**, then run the last command again). Note: the −25 for repeated failures stays for an hour, because it is stored in the database |
| Code asked although you expected a direct login | The wallet has the second step on, and this browser is not trusted yet: enter the code once |
| No e-mail in Mailpit | Check that Docker Desktop and Mailpit are running, and that the four `$env:MAIL_…` lines were run **before** `mvnw.cmd`. The address must be **Confirmed**. At most 3 alerts per hour. The wallet must already have another device |
| Backend stops with a blockchain error | Terminal 1 is not running, or the contract was deployed twice. Restart Terminal 1, deploy once, reset the data, start the backend |
| First click after a break is slow | Neon wakes the sleeping database; wait a few seconds |
| Reactivation did not happen | It waits 1 minute in the demo; Controls → Waiting actions shows the status |

---

## 10. Free a port that is still in use

If a terminal says a port is already in use, find the program that holds it. Port 8080 is the backend, 5173 the website and 8545 the
blockchain; change the number as needed:

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen | Select-Object -ExpandProperty OwningProcess
```

Stop that program (put its number in place of `<number>`):

```powershell
Stop-Process -Id <number>
```

To stop the mail inbox after the demo:

```powershell
cd C:\BlockAuth
```

```powershell
docker compose --profile mail stop mailpit
```

---

## 11. What was checked for this guide

- **Checked against the code:** every label, button text, message and number in this guide.
- **Commands checked on this laptop:**
  - the test-database line (it sets `DB_URL` to the test branch, not the main one);
  - the deploy address and its printed text;
  - the Mailpit command;
  - the e-mail settings, which were used in the live tests;
  - the reset SQL (run on the test branch inside a transaction that was cancelled, so nothing was deleted).
- **Flows checked in a live browser test (24 November 2026):**
  - every risk situation in sections 4 and 5, with the database, the admin API and the screen agreeing;
  - new-device e-mails;
  - the second step;
  - revocation;
  - delayed reactivation;
  - API keys;
  - the emergency stop.

  Results: `BLOCKAUTH_LIVE_TEST_REPORT.md` and `BLOCKAUTH_NEW_FEATURES.md`; screenshots: `BlockAuth-Feature-Screenshots/`.
- **Not checked, practise them yourself:**
  - **Real MetaMask windows.** The live test used a stand-in that makes real signatures.
  - **The exact Antigravity menu names.**
  - **The second Chrome profile with MetaMask** (section 2.2).
