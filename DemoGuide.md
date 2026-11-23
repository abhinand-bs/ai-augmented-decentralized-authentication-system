# BlockAuth — Demo Guide for the Teacher Presentation

This guide takes you through the whole demo, from switching on the computer to answering questions. Every
step has two parts:

- **What we should show:** what to click, open or run.
- **What we should say:** sentences you can say out loud. Change the words if you like, but keep the facts.

Everything in this guide exists in the project and was checked against the code. The project has **no AI
features**. Section E explains how to answer if the teacher asks about AI.

**Demo length:** about 20 minutes for Part B, plus questions. Steps marked *(optional)* can be skipped if
time is short.

---

## Part A — Before the demo

### A1. The day before

Check these once, calmly, the day before.

| Check | How |
|---|---|
| Java 21, Node.js 22 and Git are installed | `java -version`, `node -v` in a terminal |
| Chrome has the **MetaMask** extension | Open MetaMask; you need at least **three accounts** (see below) |
| `C:\BlockAuth\.env.local` is filled in | It already is on this laptop. Never show this file on screen: it holds secrets. |
| Your **admin** MetaMask address is in `BLOCKAUTH_ADMIN_WALLETS` in `.env.local` | It must be there **before** the admin wallet registers |
| `npm install` was run once in `contracts` and in `frontend` | Only needed the first time |
| You can open the Neon console in Chrome | Needed only for the database part (Step 18) |
| Do one full practice run of Part B | Then you know how long each step takes |

**The three MetaMask accounts:**

1. **Admin account**: its address is in `BLOCKAUTH_ADMIN_WALLETS`.
2. **User account**: a normal user.
3. **Stranger account**: never registered. Used to show that unknown wallets are refused.

The accounts need **no money (ETH)**. Users only sign messages; they never send transactions.

**Optional for Step 10:** an authenticator app on a phone (Google Authenticator, Microsoft Authenticator,
or similar).

### A2. Thirty minutes before the demo: start the project

Open **three terminals** in Antigravity. Run the commands one by one.

**Terminal 1: the blockchain** (leave it running and keep it visible; transactions appear here)

```powershell
cd C:\BlockAuth\contracts
```

```powershell
npx hardhat node
```

**Terminal 2: deploy the contract, then start the backend**

```powershell
cd C:\BlockAuth\contracts
```

For the demo, make reactivation wait **1 minute instead of 10**, so you can show it working live:

```powershell
$env:REGISTRY_DELAY_SECONDS = "60"
```

```powershell
npx hardhat run scripts/deploy.js --network localhost
```

It must print `WalletRegistry deployed to 0x5FbDB2315678afecb367f032d93F642f64180aa3 … delay 60s`.

```powershell
cd C:\BlockAuth\backend
```

This line makes the backend use the **test** database for this terminal only, so the production database
is not changed:

```powershell
Get-Content ..\.env.local | Where-Object { $_ -match '^TEST_DB_(URL|USERNAME|PASSWORD)=(.*)$' } | ForEach-Object { Set-Item "env:DB_$($Matches[1])" $Matches[2] }
```

The backend's own waiting time must match the 1-minute demo delay:

```powershell
$env:ADMIN_ACTION_DELAY = "1m"
```

```powershell
.\mvnw.cmd spring-boot:run
```

Wait until you see `Blockchain verified: … delay PT1M` and `Started BlockAuthApplication`.

**Terminal 3: the website**

```powershell
cd C:\BlockAuth\frontend
```

```powershell
npm run dev
```

**Then:**

1. Open **http://localhost:5173** in **Chrome** (the one with MetaMask).
2. Open **http://localhost:8080/actuator/health** in another tab. It must show `{"status":"UP"}`.
3. Close that tab.

### A3. Last checks (5 minutes before)

- [ ] All three terminals are running, with no red error at the end.
- [ ] The home page loads and shows **Connect Wallet**.
- [ ] MetaMask is unlocked and set to the **User account** (you start with the user).
- [ ] Do **not** register anything before the demo. The fresh chain should be empty, so the teacher sees
  everything happen.
- [ ] Do **not** run the backend's integration tests (`mvnw verify`) now: they empty the test database.
- [ ] Close other windows, turn off notifications, and zoom Chrome to 110–125% so everyone can read.
- [ ] Optional: open `README.md` in Antigravity's Markdown preview, at the **System architecture**
  diagram (for Step 2).

---

## Part B — The demo, step by step

### Step 1 — Introduce the project (1 minute)

**What we should show:**
- The BlockAuth home page in Chrome.

**What we should say:**
> "Good morning. Our project is called **BlockAuth — a blockchain-based authentication system**.
> Most websites use passwords. Passwords can be guessed, reused on many sites, and stolen when a database
> leaks. In BlockAuth there is **no password at all**. You prove who you are by signing a one-time message
> with your crypto wallet, MetaMask. A smart contract on a blockchain keeps the official list of which
> wallets are allowed to sign in. The server lets you in only if your signature is correct **and** the
> blockchain says your wallet is registered and active."

### Step 2 — Show the architecture (1–2 minutes)

**What we should show:**
- The **System architecture** diagram in `README.md` (Antigravity's Markdown preview, or the README on
  GitHub).
- Point at each box from left to right.

**What we should say:**
> "The project has four parts.
> One: a **React website**. That is what the user sees.
> Two: **MetaMask**, the wallet. It keeps the private key and signs messages. The key never leaves it.
> Three: a **Java Spring Boot backend**. It checks signatures, talks to the blockchain and manages sessions.
> Four: a **smart contract written in Solidity** on a local Ethereum blockchain, plus a **PostgreSQL
> database** on Neon for sessions and logs.
> The blockchain decides *whether* a wallet may sign in. The database keeps everything else, like sessions,
> history and security logs."

### Step 3 — Show that everything is running (1 minute)

**What we should show:**
- The three terminals:
  - **Terminal 1:** the local blockchain, showing the contract deployment.
  - **Terminal 2:** the backend, showing the line `Blockchain verified: chain 31337, WalletRegistry
    deployed, operator matches, guardian …`.
  - **Terminal 3:** the website.

**What we should say:**
> "Here the blockchain is running locally with Hardhat. We deployed our smart contract, `WalletRegistry`.
> When the backend starts, it checks the blockchain first: the right chain, that the contract really exists,
> and that the backend's key is allowed to manage it. If anything is wrong, the backend refuses to start.
> This stops us from running against the wrong blockchain by mistake."

### Step 4 — An unknown wallet is refused (1 minute)

**What we should show:**
1. In MetaMask, switch to the **Stranger account**.
2. On the website, click **Login**, then **Connect Wallet**, and approve in MetaMask.
3. Click **Sign Challenge**. MetaMask shows a sign-in request; click **Sign** (or **Confirm**).
4. The page shows: **"This wallet is not registered."** with a link **Register this wallet**.

**What we should say:**
> "First, let's see what happens with a wallet that was never registered. I connect the wallet and sign the
> message. The signature is real, because I really own this wallet. But the server asks the blockchain, and
> the blockchain says this wallet is not registered. So the login is refused. Owning a wallet is not
> enough; it must also be registered on the blockchain."

### Step 5 — Register a normal user (2 minutes)

**What we should show:**
1. In MetaMask, switch to the **User account**.
2. Click **Register** in the top menu.
3. Click **Connect Wallet** if asked.
4. Point at step 2 on the page and its text: "It is not a transaction: it costs no gas and moves no funds."
5. Click **Sign & Register**. While MetaMask is open, point at the page. It shows the **exact message** and
   a **countdown** ("This request expires in …s").
6. Before signing, point at the first line of the MetaMask message: `localhost:5173 wants you to sign in
   with your Ethereum account`.
7. Click **Sign** in MetaMask.
8. The page shows **"Registration complete."** Open **Registration details** and point at the
   **Transaction** hash.
9. Point at **Terminal 1**: a new transaction appeared (`eth_sendRawTransaction`).

**What we should say:**
> "Now I register a new user. The server creates a one-time message. It contains a random number, the
> website's name, the blockchain's ID and an expiry time of two minutes. MetaMask shows me exactly what I am
> signing.
> After I sign, the server checks that the signature really comes from this wallet. Then the server itself
> writes the wallet into the smart contract. You can see the blockchain transaction here, and its hash on the
> page. The user paid nothing: the server's own account sends the transaction. Registration does not log me
> in yet; I log in next."

### Step 6 — Log in as the user (2 minutes)

**What we should show:**
1. Click **Log in with this wallet** (or **Login** in the menu).
2. Click **Sign Challenge**, then **Sign** in MetaMask.
3. You land on the **Dashboard**. Point at:
   - **Wallet address**;
   - **Session: Authenticated by wallet signature**;
   - **Account status: Active**;
   - **Role: User**.

**What we should say:**
> "Now I log in. Again the server gives me a new one-time message, and I sign it. The server does three
> checks.
> First, is the message real, not expired, and never used before? This stops someone from replaying an old
> signature.
> Second, does the signature belong to this wallet?
> Third, does the smart contract say this wallet is registered and active?
> Only if all three are true does the server open a session. The session is kept on the server. The browser
> only gets a cookie that JavaScript cannot read, so the website never stores any login secret."

### Step 7 — Authentication history (1 minute)

**What we should show:**
1. Click **History** in the menu.
2. Point at the rows: **Registration – Success** and **Login – Success**, with date, time and IP address.
3. Use the **Event** filter, for example choose "Login".

**What we should say:**
> "Every attempt is recorded, also the failed ones. The user can see their own history here. If a user sees
> a failed attempt they did not make, they know someone tried to use their wallet address. Without the
> private key, that person cannot sign in."

### Step 8 — Wallet status from the blockchain (1 minute)

**What we should show:**
1. Click **Wallet status**.
2. Point at **On-chain status: Active**, **Registered: Yes**, and the registration time.
3. Click **Refresh from blockchain**.

**What we should say:**
> "This page reads the status directly from the smart contract, not from our database. The blockchain is the
> source of truth for whether a wallet is allowed. The database only keeps a copy, and the server corrects
> that copy every time it reads the blockchain."

### Step 9 — Account security: sessions and devices (1 minute)

**What we should show:**
1. Click **Account security**.
2. Point at **Signed-in sessions** ("Chrome on Windows – This device") and **Devices**.

**What we should say:**
> "Here the user sees every place where their wallet is signed in, and every browser it has used. If they
> see something strange, they can sign that session out. The server checks the session record on every
> single request, so a signed-out session stops working immediately. If the wallet signs in on a new
> device, the user's other sessions show a warning with 'This was me' and 'This was not me'."

### Step 10 — The second step with an authenticator app *(optional, 2 minutes)*

**What we should show:**
1. Still on **Account security**, scroll to **Second step** and click **Set up the second step**.
2. On the phone, add an account in the authenticator app and type in the **Key** shown on the page.
3. Type the 6-digit code from the phone into **Code shown in your app**.
4. Click **Turn on**. MetaMask asks for a confirmation signature; click **Sign**.
5. Point at the **10 backup codes** and the **On** badge.

**What we should say:**
> "Users can add a second step, like two-factor authentication. It uses standard 6-digit codes from any
> authenticator app, and 10 one-time backup codes. The server keeps the secret encrypted with AES-256, and
> stores the backup codes only as keyed hashes.
> The second step is asked for when a sign-in looks unusual, for example from a new device or network. The
> server gives every sign-in a trust score from 100 down. Notice also that turning it on needed a fresh
> MetaMask signature. Important changes always need a new signature, not just the session."

### Step 11 — Changing accounts signs you out (30 seconds)

**What we should show:**
1. In MetaMask, switch to the **Admin account**.
2. The website signs out by itself and shows **"Wallet changed — you have been signed out."**

**What we should say:**
> "A session belongs to one wallet. When I switch to another account in MetaMask, the website notices and
> signs me out at once. Now I log in as the administrator."

### Step 12 — Register and log in as administrator (1–2 minutes)

**What we should show:**
1. Click **Register**, then **Sign & Register**, and sign in MetaMask.
2. Click **Log in with this wallet**, then **Sign Challenge**, and sign.
3. You land on the **Admin dashboard**.
4. Point at the extra menu items: **Admin, Wallets, Security, Controls**.
5. Point at the numbers: users, active wallets, login attempts, failed logins, security events. The
   stranger's refused login from Step 4 is counted.

**What we should say:**
> "This wallet is an administrator, because its address is in the server's settings. The role comes from
> the server's database, never from the browser. The admin dashboard shows statistics. You can see the
> failed login from the stranger wallet we tried at the beginning."

### Step 13 — Revoke the user's wallet on the blockchain (2 minutes)

**What we should show:**
1. Click **Wallets**. Point at the list of registered users.
2. In the user's row, click **Check on-chain**. It shows **Registered and active**.
3. Click **Revoke** in the user's row. A confirmation box opens; point at the warning text.
4. Click **Revoke wallet**. MetaMask shows a message naming the action and the user's wallet; click
   **Sign**.
5. The page shows **"Wallet … is now revoked."** Open **Transaction details**.
6. Point at **Terminal 1**: a new transaction.
7. Point out that the admin's own row has its **Revoke** button disabled.

**What we should say:**
> "Now the administrator blocks the user. Revoking is written to the smart contract immediately, and all of
> the user's sessions end on their next request.
> Notice two safety rules. First, the admin had to sign a new MetaMask message that names exactly this
> action and this wallet. If someone stole the admin's session cookie, they still could not do this,
> because they don't have the admin's wallet. Second, an admin cannot revoke their own wallet."

### Step 14 — The revoked user cannot log in (1 minute)

**What we should show:**
1. In MetaMask, switch to the **User account**. The admin is signed out automatically.
2. Click **Login**, then **Sign Challenge**, and sign.
3. The page shows **"This wallet has been revoked by an administrator."**

**What we should say:**
> "The user signs correctly, but the blockchain now says the wallet is revoked, so the login is refused.
> Even if someone changed our database by hand, it would not help, because the server checks the blockchain
> at every login."

*(Optional, if you prepared a second Chrome profile with MetaMask: keep the user logged in there before Step
13. After the revoke, click any page there; it is sent to the login page with "Your session has expired or
was ended". That shows an open session being cut off.)*

### Step 15 — Reactivation must wait (2 minutes)

**What we should show:**
1. Switch MetaMask back to the **Admin account** and log in again (**Login**, then **Sign Challenge**).
2. Go to **Wallets** and click **Activate** in the user's row.
3. Click **Request reactivation** and sign in MetaMask.
4. The page shows **"Reactivation of … is requested. The server carries it out at …"**.
5. Click **Controls** and scroll to **Waiting actions**. Point at the action with status **Pending** and
   its **Takes effect** time.
6. Wait about 1–3 minutes (we set the demo delay to 1 minute). Meanwhile, go on with Step 16 and come back.
7. Then show that the status is **Executed**. On **Wallets** the user is **Active** again.

**What we should say:**
> "Giving access back is more dangerous than taking it away. So reactivation does not happen immediately.
> It waits, normally 10 minutes; for this demo we set it to one minute. During the wait, any administrator
> can cancel it, and cancelling also needs a fresh signature.
> This delay is not only in our server. It is enforced by the smart contract itself. Even with the
> server's blockchain key, nobody can reactivate a wallet without waiting. A second, separate key called the
> **guardian** can cancel such requests and replace the server's key if it is ever stolen."

### Step 16 — Security monitoring (2 minutes)

**What we should show:**
1. Click **Security**.
2. On the **Security events** tab, point at **Unregistered wallet** (Step 4) and **Revoked wallet attempt**
   (Step 14), each **Blocked**.
3. Click the **Authentication events** tab. It shows every registration, login, logout and admin action.
4. Click the **Sign-in risk** tab. Point at a **score** (for example 100), the **Why** column and the
   **Decision** (Allow).

**What we should say:**
> "The server detects and records attacks: replayed messages, expired messages, wrong signatures, unknown or
> revoked wallets, too many requests, and more. Here you can see the two attempts we made earlier.
> The sign-in risk tab shows the trust score of every login. It starts at 100 and loses points for things
> like a new device, a new network, or a wallet that an admin marked as suspicious. A low score asks for the
> second step, or refuses the login. These are fixed rules, not artificial intelligence."

### Step 17 — Controls: emergency stop and API keys *(optional, 2 minutes)*

**What we should show:**
1. Click **Controls**.
2. Read the **Emergency stop** text aloud (you don't need to switch it on).
3. Scroll to **Suspicious wallets** and **API keys**, and explain them.

**What we should say:**
> "If something goes wrong, an admin can switch on an emergency stop. It pauses registrations,
> reactivations and other risky changes, and it also pauses the smart contract, so the blockchain refuses
> them too. Admins can still sign in, and revoking still works.
> Admins can mark a wallet as suspicious, which lowers its trust score. They can also create read-only API
> keys for monitoring scripts. A key can only read, and it expires."

### Step 18 — Show the technical side (3–4 minutes)

**18a. The code**

**What we should show:** open these files in Antigravity, one by one.

| File | What to point at |
|---|---|
| `contracts/contracts/WalletRegistry.sol` | `registerWallet`, `revokeWallet`, `requestActivation`, `executeActivation`, the `onlyOperator` and `onlyGuardian` checks |
| `backend/src/main/java/com/blockauth/auth/SignatureVerifier.java` | `verify`: recovers the signer's address from the signature and compares it |
| `backend/src/main/java/com/blockauth/auth/ChallengeMessageFactory.java` | how the one-time message is built |
| `backend/src/main/java/com/blockauth/auth/LoginService.java` | the order: challenge → signature → blockchain → trust score → session |
| `backend/src/main/java/com/blockauth/blockchain/WalletRegistryClient.java` | how the backend calls the contract with Web3j |
| `backend/src/main/resources/db/migration/` | the database tables (Flyway migrations V1, V2, V3) |

**What we should say:**
> "The smart contract is short on purpose. It stores only each wallet's status and registration time, no
> personal data. Only the server's operator key can register, and only the operator or the guardian can
> revoke.
> In the backend, `SignatureVerifier` uses the Web3j library to find out which wallet produced a signature.
> `LoginService` runs every check in a fixed order, and if any step fails, the login fails. Nothing is
> skipped.
> The database tables are created by Flyway migrations, so the structure is version-controlled."

**18b. The database (Neon)**

**What we should show:**
1. Open the Neon console and choose the **test** branch (the one the demo backend uses).
2. Open the **SQL Editor** and run:

   ```sql
   select wallet_address, role, status, created_at from users;
   ```

   ```sql
   select event_type, event_status, failure_reason, occurred_at from authentication_events order by id desc limit 10;
   ```

**What we should say:**
> "This is our PostgreSQL database on Neon. The users table has the wallet, the role and a copy of the
> status from the blockchain. There is no password column anywhere. Here are the events we just created
> during the demo."

**18c. The blockchain**

**What we should show:** in a new terminal, run:

```powershell
cd C:\BlockAuth\contracts
```

```powershell
$env:GUARDIAN_ACTION = "status"; npx hardhat run scripts/guardian.js --network localhost
```

It prints the **operator**, the **guardian**, the **delay** and **paused: false**.

**What we should say:**
> "This reads the contract's settings: the operator, which is the server's key; the guardian, which is a
> separate key that should be kept offline; and the waiting delay. Only the guardian can replace the
> operator, so one stolen key does not give full control."

**18d. The tests**

**What we should show:** in the same terminal, run:

```powershell
npx hardhat test
```

It shows **21 passing** in a few seconds. Point at the test names, especially "a stolen operator key cannot
restore access at once and is undone by the guardian".

**What we should say:**
> "We have automated tests. These 21 test the smart contract. The backend has 187 unit tests and 192
> integration tests. The integration tests run the real application with real signatures against a test
> database; the last full run passed with zero failures. We also tested a list of 30 attacks, and the
> results are written in `AttackCheck.md`."

*(Do not run the backend integration tests live: they take about 20 minutes and empty the test database.)*

### Step 19 — Finish the demo (1 minute)

**What we should show:**
- Go back to the home page, or to the README architecture diagram.

**What we should say:**
> "To sum up: BlockAuth replaces passwords with wallet signatures. Every sign-in message works only once
> and expires after two minutes, so it cannot be replayed. The smart contract is the authority on which
> wallets are allowed. Administrators can revoke a wallet immediately, but giving access back must wait,
> and that rule is enforced on the blockchain itself. Everything is logged and monitored, and the project is
> covered by automated tests.
> We also know the limits: it runs on a local blockchain, it needs MetaMask on a desktop browser, and if a
> user loses their wallet's key, there is no recovery. Thank you. We are happy to answer your questions."

---

## Part C — How the main features work (for your own understanding)

| Feature | Problem it solves | How it works in short |
|---|---|---|
| Wallet sign-in | Passwords can be guessed, reused and stolen | The user signs a one-time message; the server works out who signed it |
| One-time messages | Someone could reuse a captured signature | Random number, 2-minute expiry, marked "used" the first time it is tried |
| On-chain registry | Account status in a database can be changed silently | The smart contract holds registered / active / revoked; only the server's operator key (and the guardian) can change it |
| Server sessions | Tokens stored in the browser can be stolen by scripts | The session lives on the server; the cookie is HttpOnly; it is checked on every request |
| Revocation | A stolen wallet must be cut off fast | Written to the blockchain at once; the user's sessions end |
| Delayed reactivation | A stolen admin account could give access back quietly | Waits 10 minutes (on the server **and** in the contract); any admin can cancel |
| Fresh signatures | A stolen session cookie should not be enough | Dangerous actions need a new MetaMask signature naming the action |
| Second step | A signature alone may not be enough for unusual sign-ins | Authenticator codes plus backup codes, asked for when the trust score is low |
| Trust score | Spot unusual sign-ins | Fixed rules: new device −30, new network −20, flagged wallet −35, and so on |
| Rate limiting | Brute force and floods | 10 sign-in requests a minute per address; 10 failures block that address for 15 minutes |
| Emergency stop | Stop damage during an incident | Pauses risky actions in the server and pauses the contract |
| Security logs | Know what happened | Every attempt and every detected attack is stored and shown to admins |

---

## Part D — Questions the teacher might ask, with answers

**1. Why use a blockchain at all? Why not just a database?**
> "The blockchain holds only one thing: whether a wallet is registered and active. A database row can be
> changed silently by anyone with database access. A change in the contract needs the operator key and
> leaves a permanent event on the chain. So the most important decision, who may sign in, cannot be changed
> by just editing our database."

**2. Where is the password stored?**
> "There is no password. The user proves who they are with a signature from their wallet. The server stores
> no secret for the login, only the public wallet address."

**3. How does the server know the signature is real?**
> "From a signature and the original message, you can calculate which wallet address signed it. This is
> standard Ethereum cryptography called EIP-191. The server does this calculation with the Web3j library
> and compares the result with the wallet the user claimed."

**4. What if a hacker copies the signature and sends it again?**
> "That is a replay attack. Each message has a random number and works only once. The first time it is
> used, the server marks it as used in the database in one single step, so even two requests at the same
> moment cannot both succeed. It also expires after two minutes."

**5. What if someone signs with a different wallet?**
> "The server calculates the real signer and compares it with the claimed wallet. If they differ, the
> login fails with 'signature mismatch', and it is logged as an attack."

**6. Does the user pay gas fees?**
> "No. Users only sign messages, which is free. The server's own account sends the few blockchain
> transactions: registering, revoking and reactivating. On our local test blockchain this costs nothing."

**7. What happens if the user loses their private key or seed phrase?**
> "Then they cannot sign in with that wallet again. There is no recovery, because nobody else has the key.
> That is a real limitation of wallet-based login. If the key is stolen, an admin can revoke the wallet."

**8. What if the server's blockchain key is stolen?**
> "We designed the contract with two keys. The thief could still revoke wallets, but could not give access
> back immediately: reactivations and lifting a pause must wait on the blockchain. Our server also cancels
> any request it did not make. And the guardian key, which is kept offline, can replace the stolen key."

**9. Is the project decentralized?**
> "Partly. The wallet status lives on a blockchain, but our server is still needed, and for this project
> the blockchain is a local test network. We don't claim it is fully decentralized."

**10. Does the project use AI?**
> "No. There is no artificial intelligence or machine learning in the project. The trust score is a set of
> fixed rules that we wrote. We kept it rule-based on purpose, so every decision can be explained and tested."

*(The GitHub repository has an older name containing "ai-augmented". The current code has no AI, so do not
present any AI feature.)*

**11. Why server sessions and not JWT tokens?**
> "With server sessions, we can end a session at any moment, for example when a wallet is revoked, and the
> next request is refused. The session cookie is HttpOnly, so JavaScript on the page cannot steal it."

**12. How do you stop brute-force attacks?**
> "Each address can make only 10 sign-in requests per minute. After 10 failed sign-ins in 15 minutes, that
> address is blocked for 15 minutes. We block the address, not the wallet, so an attacker cannot lock a real
> user out on purpose."

**13. How did you test it?**
> "21 smart contract tests, 187 backend unit tests and 192 integration tests. The integration tests run the
> real application with real wallet signatures against a separate test database. We also ran OWASP ZAP
> security scans and checked 30 attacks, documented in `AttackCheck.md`."

**14. Where is the data stored?**
> "On the blockchain: only each wallet's status and registration time. In PostgreSQL on Neon: users, roles,
> sessions, devices, the second-step data, and logs. Secrets are never stored in plain form: they are hashed
> or encrypted."

**15. What technologies did you use?**
> "React and Vite for the website, Java 21 with Spring Boot for the backend, Web3j to talk to the
> blockchain, Solidity and Hardhat for the smart contract, PostgreSQL on Neon with Flyway migrations, and
> MetaMask as the wallet."

**16. Can it work on a phone?**
> "Not at the moment. It needs the MetaMask extension in a desktop browser like Chrome."

**17. What would you improve next?**
> "Deploy it to a public test network, add e-mail alerts for new sign-ins, support more than one backend
> server, and have the smart contract reviewed by an external auditor."

---

## Part E — Things not to say

- Do **not** say the project uses AI or machine learning. It does not.
- Do **not** say it is "100% secure" or "unhackable". Say "we tested 30 attacks and fixed what we found."
- Do **not** say it runs on Ethereum mainnet. It runs on a local Hardhat blockchain.
- Do **not** say lost keys can be recovered. They cannot.
- Do **not** open `.env.local` on the screen: it contains passwords and keys.

---

## Part F — If something goes wrong during the demo

| What you see | What to do |
|---|---|
| "MetaMask is required" | You are not in Chrome with MetaMask, or MetaMask is locked. Unlock it and reload the page. |
| Nothing happens after clicking Sign | MetaMask has a waiting request. Click the MetaMask icon and answer it. |
| "This wallet is not registered" for a wallet you registered before | The blockchain was restarted, which erases it. Deploy again (Terminal 2) and register again. |
| Backend stops with a blockchain error at start | The contract is not deployed on the running blockchain, or its delay is longer than `ADMIN_ACTION_DELAY`. Deploy again with `$env:REGISTRY_DELAY_SECONDS = "60"`, and set `$env:ADMIN_ACTION_DELAY = "1m"`. |
| "Port 8080 was already in use" | Another program uses the port. Close the other backend, or restart the laptop before the demo. |
| "Too many failed attempts. Try again later." | You failed 10 times. Wait 15 minutes, or restart the backend (Ctrl+C, then run it again). |
| The first page after a break is slow | Neon wakes up the database on the first request. Wait a few seconds; it is normal. |
| Logged in as admin, but the role shows "User" | The admin address was not in `BLOCKAUTH_ADMIN_WALLETS` when it registered. Fix `.env.local`, restart the blockchain and the backend, deploy again, and register again. |
| The reactivation in Step 15 still says Pending | It runs every 15 seconds after the waiting time, and if the blockchain's wait is a few seconds behind, it tries again one minute later. Give it up to 3 minutes. Continue with Step 16 and come back. |
| The admin is asked for a verification code at login | The admin turned on the second step and this sign-in looks new. Type the code from the phone, or a backup code. |

**After the demo:** press **Ctrl+C** in Terminal 3, then Terminal 2, then Terminal 1.
