# BlockAuth — Feature Guide for the Demo

This guide explains every feature of BlockAuth for a **normal user** and for an **admin**, and which
features were **added** beyond the original plan. It was written from the actual code. Each feature has:

- **What it does:** in simple words.
- **Purpose:** why we need it.
- **How to demonstrate it:** what to click.
- **What we should say to Mam:** one or two sentences to speak.

> **Important:** the trust score is a set of fixed rules we wrote **plus** a small unsupervised anomaly model
> (machine learning, an Isolation Forest). The model runs in "shadow" mode by default: it is shown to the
> admin but changes no decision. It is a risk indicator, never proof of identity.

---

## 1. Normal User Account

A normal user is anyone whose wallet is registered and who is **not** in the admin list.

### 1.1 Connect Wallet

- **What it does:** Connects the website to the user's MetaMask wallet and shows the wallet address.
- **Purpose:** The website must know which wallet the user wants to use. Only the public address is shared,
  never the secret key.
- **How to demonstrate it:** Click **Login** or **Register**, then **Connect Wallet**, then **Connect** in
  MetaMask. The page shows **Connected 0x…**.
- **What we should say to Mam:** "We connect MetaMask. The website only receives the public address. The
  private key always stays inside MetaMask."

### 1.2 Register Wallet

- **What it does:** The user signs a one-time message. The server checks the signature and then writes the
  wallet into the smart contract on the blockchain.
- **Purpose:** Only registered wallets may log in. The blockchain keeps the official list.
- **How to demonstrate it:**
  1. Click **Register**, then **Sign & Register**, then **Sign** in MetaMask.
  2. The page shows **Registration complete**.
  3. Click **Registration details** to show the transaction hash.
  4. Show the new transaction in the blockchain terminal.
- **What we should say to Mam:** "The user proves they own the wallet by signing. Then our server writes the
  wallet to the blockchain. The user pays nothing; our server sends the transaction."

### 1.3 Login with a Wallet Signature (no password)

- **What it does:** The server gives a new one-time message. The user signs it. The server checks the
  signature and asks the blockchain whether the wallet is registered and active. Then it starts a session.
- **Purpose:** Passwords can be guessed, reused and stolen. Here there is no password at all.
- **How to demonstrate it:**
  1. Click **Login**, then **Sign Challenge**.
  2. While MetaMask is open, point at the exact message and the countdown on the page.
  3. Click **Sign** in MetaMask. You land on the **Dashboard**.
- **What we should say to Mam:** "Every login message is new, works only once, and expires in two minutes.
  So an old signature can never be reused."

### 1.4 Clear Error Messages

- **What it does:** When something fails, the page explains it in simple words and says what to do next. A
  small **Technical details** section shows the error code.
- **Purpose:** Users understand the problem; developers can still find the exact cause.
- **How to demonstrate it:** Log in with a wallet that is not registered. The page shows **"This wallet is
  not registered."** and a **Register this wallet** link.
- **What we should say to Mam:** "Errors are explained in plain words, with a hint on what to do next."

### 1.5 Dashboard

- **What it does:** Shows the user's wallet address, session state, account status, role and registration
  date, plus quick links.
- **Purpose:** The user sees at once who they are signed in as.
- **How to demonstrate it:** After login, point at **Wallet address**, **Session: Authenticated by wallet
  signature**, **Account status: Active** and **Role: User**.
- **What we should say to Mam:** "The dashboard shows the user's wallet, status and role. The role comes from
  the server, not from the browser."

### 1.6 Authentication History

- **What it does:** Lists every registration, login, logout and security change for this wallet, including
  failed attempts. It has a filter by event type and pages of 20 rows.
- **Purpose:** If someone else tries to use the wallet address, the user can see it.
- **How to demonstrate it:** Click **History**, show the rows, then change the **Event** filter.
- **What we should say to Mam:** "Every attempt is recorded, even failed ones. A user only ever sees their
  own history."

### 1.7 Wallet Status from the Blockchain

- **What it does:** Reads the wallet's status directly from the smart contract: registered, active or
  revoked, and the registration time.
- **Purpose:** The blockchain is the official source of truth, not our database.
- **How to demonstrate it:** Click **Wallet status**, then **Refresh from blockchain**.
- **What we should say to Mam:** "This information comes straight from the smart contract, so nobody can fake
  it by editing our database."

### 1.8 Signed-in Sessions *(added feature)*

- **What it does:** Lists every place where the wallet is signed in: browser, IP address, sign-in time and
  last activity. The user can sign out one session, or **Sign out everywhere else**.
- **Purpose:** If a user sees a session they don't recognise, they can end it at once.
- **How to demonstrate it:** Click **Account security** and point at **Signed-in sessions** and the **This
  device** label.
- **What we should say to Mam:** "The user can see and end their sessions. The server checks the session on
  every request, so an ended session stops working immediately."

### 1.9 Devices *(added feature)*

- **What it does:** Lists the browsers this wallet has used, with first and last use, and whether the device
  is trusted. **Forget** signs that device out.
- **Purpose:** The server can recognise a known browser. A new browser counts as riskier.
- **How to demonstrate it:** On **Account security**, point at the **Devices** table.
- **What we should say to Mam:** "Each browser gets a random secret cookie. The server stores only a hash of
  it, and uses it to tell known devices from new ones."

### 1.10 New Sign-in Alerts *(added feature)*

- **What it does:** When the wallet signs in on a new device, the user's other sessions show a warning on
  every page. They can click **This was me**, or **This was not me**, which signs that device out.
- **Purpose:** It warns the real owner quickly if someone else signs in.
- **How to demonstrate it:** *(Needs a second browser with MetaMask.)*
  1. Sign in with the same wallet in a second browser.
  2. In the first browser, a warning appears.
  3. Open **Account security** and click **This was not me**.
- **What we should say to Mam:** "If the wallet signs in somewhere new, the user is warned and can sign that
  device out with one click."
- **E-mail (optional):** on Account security the user can add an alert e-mail address. Setting it needs a
  wallet signature and a 6-digit code sent to the address. The server then also e-mails new-device sign-ins
  (plain text, no links). It is off unless the server has a mail server configured; there are no SMS or phone
  notifications.

### 1.11 Second Step (Authenticator App and Backup Codes) *(added feature)*

- **What it does:** The user can turn on 6-digit codes from an authenticator app, plus 10 one-time backup
  codes. The code is asked for when a sign-in looks unusual, for example from a new device or network. The
  user can also turn it off, or replace the backup codes.
- **Purpose:** It adds extra protection on top of the wallet signature.
- **How to demonstrate it:**
  1. On **Account security**, click **Set up the second step**.
  2. Type the **Key** into an authenticator app on your phone.
  3. Type in the 6-digit code and click **Turn on**.
  4. Sign in MetaMask. Show the 10 backup codes.
- **What we should say to Mam:** "It works like two-factor authentication. The secret is stored encrypted on
  the server, and each code works only once."
- **Note:** The page shows a text key and a link, not a QR code, so the key is typed into the app by hand.

### 1.12 Trust Score *(added feature, works in the background)*

- **What it does:** Every login gets a score from 100 down. Points are taken off for:
  - a new device (30);
  - a new network (20);
  - a wallet marked suspicious (35);
  - repeated failures from the same address (25);
  - sharing a device with a suspicious wallet (35).

  The score decides what happens:
  - **90 or more:** the signature is enough.
  - **Lower, and the wallet has a second step:** the code is asked for.
  - **Below 50, and there is no second step:** the login is refused.
- **Purpose:** It catches logins that don't look normal.
- **How to demonstrate it:** The user cannot see it. Show it later as admin under **Security → Sign-in
  risk**.
- **Between 50 and 89 without a second step:** the signature alone is enough (the login is allowed).
- **Anomaly model:** beside the rules, an unsupervised model scores how unusual the login is and names the two
  things that made it unusual (for example "new device, hour"). By default it is only shown to the admin.
- **What we should say to Mam:** "Each login is scored with simple fixed rules, and a small anomaly model also
  says how unusual it looks. A risky login needs the second step. Every rule decision can be explained, and the
  model is only a risk indicator."

### 1.13 Logout and Automatic Sign-out

- **What it does:**
  - **Logout** ends the session on the server.
  - The user is also signed out automatically if they switch accounts in MetaMask, after 30 minutes without
    activity, if an admin revokes their wallet, or if they end the session from another device.
- **Purpose:** A session must never stay open longer than it should.
- **How to demonstrate it:** Switch accounts in MetaMask. The page shows **"Wallet changed — you have been
  signed out."**
- **What we should say to Mam:** "A session belongs to one wallet. If the wallet changes, the session ends at
  once."

### 1.14 Fresh Signature for Important Changes *(added feature)*

- **What it does:** Turning the second step on or off, and replacing backup codes, need a new MetaMask
  signature. The session alone is not enough.
- **Purpose:** If someone steals the session cookie, they still cannot change security settings.
- **How to demonstrate it:** When you click **Turn on** (1.11), MetaMask asks you to sign a confirmation
  naming the action.
- **What we should say to Mam:** "Important changes need a new signature from the wallet, so a stolen session
  cookie is not enough."

---

## 2. Admin User Account

An admin is a wallet whose address is in the server's admin list (`BLOCKAUTH_ADMIN_WALLETS`) when it
registers. **An admin also has every normal-user feature from Section 1.** The admin menu adds **Admin,
Wallets, Security and Controls**.

### 2.1 Admin Dashboard (Statistics)

- **What it does:** Shows the total numbers:
  - users, active wallets and revoked wallets;
  - login attempts: successful, failed and blocked;
  - reasons for failure: invalid signature, replayed message, revoked wallet;
  - the number of security events, with a bar for each attack type.
- **Purpose:** The admin sees the health of the system at a glance.
- **How to demonstrate it:** Log in as admin; you land on **Admin dashboard**. Click **Refresh**.
- **What we should say to Mam:** "The admin sees all the numbers at once: users, logins, failures and attacks
  that were blocked."

### 2.2 Wallet Management — User List

- **What it does:** Lists every registered wallet with its role, status, registration date and last change.
  It has filters for status and role, and pages of 20 rows.
- **Purpose:** The admin can find and manage any user.
- **How to demonstrate it:** Click **Wallets**, then use the **Status** and **Role** filters.
- **What we should say to Mam:** "Here the admin sees every registered wallet and can filter them."

### 2.3 Check Any Wallet on the Blockchain

- **What it does:** Reads any wallet's status directly from the smart contract.
- **Purpose:** The admin can check the truth on the blockchain, not just our database copy.
- **How to demonstrate it:** On **Wallets**, type an address in **Check any wallet on the blockchain** and click
  **Check on-chain**, or click **Check on-chain** in a row.
- **What we should say to Mam:** "The admin can check any wallet directly on the blockchain."

### 2.4 Revoke a Wallet

- **What it does:** Blocks a wallet. It is written to the smart contract at once, and all of that user's
  sessions end on their next request.
- **Purpose:** A stolen or misused wallet can be stopped immediately.
- **How to demonstrate it:**
  1. Click **Revoke** in a user's row, then **Revoke wallet** in the box that opens.
  2. Sign in MetaMask. The page shows **"Wallet … is now revoked."**
  3. Show the transaction in the blockchain terminal.
  4. Then log in as that user: it is refused.
- **What we should say to Mam:** "Revoking is written to the blockchain immediately. The user is signed out
  and cannot log in again."
- **Note:** An admin cannot revoke their own wallet; that button is disabled.

### 2.5 Reactivate a Wallet (with a Waiting Time) *(added feature)*

- **What it does:** Requests to unblock a wallet. The request waits (10 minutes by default) before it happens.
  Any admin can cancel it during the wait.
- **Purpose:** Giving access back is risky. The wait stops a thief who took over an admin account from
  quietly giving access back.
- **How to demonstrate it:**
  1. Click **Activate** in a revoked user's row, then **Request reactivation**, and sign in MetaMask.
  2. Go to **Controls → Waiting actions**. It shows **Pending**.
  3. After the wait, it shows **Executed**.
- **What we should say to Mam:** "Blocking is immediate, but unblocking must wait, and any admin can cancel
  it. The smart contract itself enforces this wait."

### 2.6 Security Monitoring *(the risk tab was added)*

- **What it does:** Has three tabs, each with filters (type, status, wallet):
  - **Security events:** attacks the system detected and blocked, for example a replayed message, a wrong
    signature, an unknown or revoked wallet, too many requests, a failed confirmation signature.
  - **Authentication events:** every registration, login, logout and admin action.
  - **Sign-in risk** *(added)*: the trust score of every login, why points were taken off, the decision, the
    anomaly score with the two features that made the login unusual, a filter for a minimum anomaly score, and a
    card showing the model's mode, version and training data.
- **Purpose:** The admin can see attacks and investigate what happened.
- **How to demonstrate it:** Click **Security** and switch between the three tabs.
- **What we should say to Mam:** "Every attack the server stops is recorded here, with the time, wallet and
  address."

### 2.7 Emergency Stop *(added feature)*

- **What it does:** One switch pauses risky actions: new registrations, reactivations, second-step resets,
  removing suspicious flags and creating API keys. Optionally it also blocks sign-ins by normal users, or
  signs every user out. Admins can still sign in, and revoking still works. Lifting the stop waits like
  other access-giving actions. It needs a reason and a MetaMask signature.
- **Purpose:** If there is an incident, the admin can freeze the system quickly.
- **How to demonstrate it:** Click **Controls** and read the **Emergency stop** section. Switching it on is
  optional.
- **What we should say to Mam:** "If something goes wrong, one switch pauses risky actions. It also pauses the
  smart contract."

### 2.8 Waiting Actions *(added feature)*

- **What it does:** Lists the delayed admin actions: reactivations, second-step resets and lifting the
  emergency stop. Each shows its status: Pending, Executed, Cancelled or Failed. Any admin can **Cancel** a
  pending one.
- **Purpose:** All admins can see and stop a dangerous request before it happens.
- **How to demonstrate it:** On **Controls**, scroll to **Waiting actions** and show the status filter.
- **What we should say to Mam:** "Every delayed action is visible here, and any admin can cancel it in time."

### 2.9 Reset a User's Second Step *(added feature)*

- **What it does:** Helps a user who lost both their authenticator and their backup codes. The reset waits
  before it happens.
- **Purpose:** It gives a recovery path for the second step, without giving instant access.
- **How to demonstrate it:** On **Controls**, show **Reset a user's second step**. Type a wallet address and
  click **Request reset** (optional).
- **What we should say to Mam:** "If a user loses their phone, an admin can reset the second step, but only
  after a waiting time."

### 2.10 Suspicious Wallets (Flags) *(added feature)*

- **What it does:** The admin can mark a wallet as suspicious, with a reason, or remove the mark. A flag
  lowers the trust score of that wallet, and of wallets that shared a device with it. It does not block the
  wallet.
- **Purpose:** It gives extra caution for wallets that look risky.
- **How to demonstrate it:** On **Controls**, show **Suspicious wallets**. Type an address and a reason, then
  click **Flag wallet** (optional).
- **What we should say to Mam:** "Flagging does not block a wallet. It makes its logins need extra checks."

### 2.11 Read-only API Keys *(added feature)*

- **What it does:**
  - The admin creates keys for scripts or monitoring tools, with a name, scopes and a lifetime.
  - The scopes are: read users, read logs and statistics, read wallet status.
  - A key can only read, and lasts 1 to 90 days.
  - It is shown once only; the server keeps only a hash of it.
  - The admin can revoke a key; the page asks for confirmation first.
- **Purpose:** Monitoring tools can read data safely without an admin session.
- **How to demonstrate it:** On **Controls**, show **API keys**. Creating one is optional.
- **What we should say to Mam:** "API keys let other programs read our data, but never change anything."

### 2.12 Fresh Signature for Every Admin Change *(added feature)*

- **What it does:** Every admin change needs a new MetaMask signature that names the exact action, for
  example "Revoke wallet 0x…". This covers:
  - revoke and reactivate a wallet;
  - the emergency stop;
  - flags;
  - second-step reset;
  - creating and revoking API keys;
  - cancelling a waiting action.
- **Purpose:** A stolen admin session cookie cannot do any of these without the admin's wallet.
- **How to demonstrate it:** Click **Revoke** (2.4). MetaMask shows a message naming that wallet.
- **What we should say to Mam:** "Even if someone steals the admin's session, they cannot change anything,
  because every change needs the admin's wallet to sign."

### What admins do **not** have

- No button to make a user an admin. Roles are set from the admin list when a wallet registers.
- No downloadable reports (PDF or Excel). Data can be read on the pages, or by scripts through API keys.
- The guardian key's actions are not on the website. They are run from a terminal script (`guardian.js`).

---

## 3. New Features We Added

The original plan had **9 pages**: Home, Register, Login, Dashboard, History, Wallet status, Admin dashboard,
Wallet management and Security monitoring (security and authentication events). Everything below was added
on top.

### 3A. Added earlier and on GitHub

| New feature | What it does | Why we added it | How it improves the project |
|---|---|---|---|
| **Account security page** (sessions, devices, sign-in alerts) | Users see and end their sessions, forget devices, and react to new-device alerts | Users had no way to see or stop other sessions | Users can protect themselves |
| **Second step** (authenticator codes and 10 backup codes) | Extra code for unusual logins | A signature alone may not be enough | Two-factor protection |
| **Trust score** and the **Sign-in risk** tab | Scores every login with fixed rules | To catch logins that look unusual | Risky logins need more proof |
| **Fresh signatures** for important actions | A new wallet signature naming the action | A stolen session should not be enough | Protects against stolen cookies |
| **Emergency stop** | One switch pauses risky actions | Fast response during an incident | Damage can be limited quickly |
| **Waiting actions** (delayed reactivation, second-step reset, lifting the stop) | Access-giving actions wait and can be cancelled | A stolen admin account could give access back quietly | Other admins have time to react |
| **Suspicious wallet flags** | Lowers a wallet's trust score | Extra caution for risky wallets | Better risk control |
| **Read-only API keys** | Safe read access for scripts | Monitoring tools need data | Safe automation |
| **Sign-In with Ethereum message format** | The message names the website, blockchain, nonce and expiry | Wallets can warn about fake sites | Better protection against phishing |
| **Black-and-white design and accessibility** | New look; page titles; better keyboard use; a confirmation before revoking an API key; checks on the key's lifetime | A cleaner, easier interface | More professional and easier to use |

### 3B. Added most recently

These are **on this laptop only and not yet on GitHub**. Commit them before the demo if you want to show
them from GitHub.

| New feature | What it does | Why we added it | How it improves the project |
|---|---|---|---|
| **Two-key smart contract** (operator plus guardian) | The server's key can register and revoke, but reactivating or un-pausing must wait on the blockchain. A separate guardian key can cancel requests, pause the contract and replace a stolen server key. | Before, one stolen server key could change any wallet instantly | One stolen key no longer gives full control |
| **Emergency stop pauses the contract too** | Registrations and reactivations are refused on the blockchain as well | Before, the stop only worked inside our server | The stop also binds the blockchain |
| **Automatic blockchain check every 15 seconds** | Signs out wallets that were revoked outside the app, and cancels blockchain requests our server did not make | Before, such changes were noticed late | Faster and safer reaction |
| **Fresh signature to cancel a waiting action or revoke an API key** | These two admin actions now need the wallet too | A stolen admin session could still do them | Every admin change needs the wallet |
| **Rate limits behind the Docker web server** | The backend now takes each user's address from nginx (and trusts only nginx) | Before, it always saw nginx's address | Each user gets their own limit where Docker keeps client addresses (Linux). On Docker Desktop (Windows, macOS) all clients still arrive from one gateway address and share one limit |
| **Security test report** | `AttackCheck.md` lists 30 attacks with results | To prove the protections work | Evidence for the demo |

---

## 4. Difference Between Normal User and Admin

| Feature | Normal user | Admin |
|---|:---:|:---:|
| Register and log in with a wallet | Yes | Yes |
| Dashboard, history, own wallet status | Yes | Yes |
| Own sessions, devices, sign-in alerts | Yes | Yes |
| Own second step (authenticator, backup codes) | Yes | Yes |
| Admin dashboard (statistics) | No | Yes |
| See all users and check any wallet on the blockchain | No | Yes |
| Revoke and reactivate wallets | No | Yes |
| Security monitoring (attacks, all events, sign-in risk) | No | Yes |
| Emergency stop | No | Yes |
| Waiting actions (view and cancel) | No | Yes |
| Reset another user's second step | No | Yes |
| Flag suspicious wallets | No | Yes |
| Create and revoke API keys | No | Yes |
| Sign in while the emergency stop blocks sign-ins | No | Yes |

**Why the permissions are different:**

- Normal users can only see and change **their own** account. The server never accepts another user's ID
  from them.
- Admins manage **everyone**, so their actions are more powerful. That is why every admin change needs a
  fresh wallet signature, and why giving access back must wait.
- The role is checked **by the server on every request**. Hiding a menu item is not the protection. If a
  normal user types an admin address into the browser, they see a "no permission" page, and the server
  refuses any admin request with **403**.
- An admin cannot revoke or reactivate their own wallet.

---

## 5. Demo Explanation for Mam (ready to speak)

Read this in order. The bracketed text tells you what to click.

### Part 1 — Introduction

> "Mam, our project is **BlockAuth, a blockchain-based authentication system**. Normal websites use
> passwords, which can be guessed or stolen. In our project there is no password. The user proves who they
> are by signing a one-time message with their MetaMask wallet. A smart contract on the blockchain keeps the
> official list of allowed wallets."

### Part 2 — The normal user

> [Login with an unregistered wallet] "First, a wallet that is not registered. The signature is real, but
> the blockchain says it is not registered, so the login is refused."

> [Register, then Sign & Register] "Now I register. I sign a message, and our server writes my wallet to the
> blockchain. Here is the transaction."

> [Login, then Sign Challenge] "Now I log in. The message is new, works once, and expires in two minutes.
> The server checks my signature and the blockchain, and then opens a session."

> [Dashboard] "This is my dashboard: my wallet, status and role."

> [History] "Every attempt is recorded, even failed ones."

> [Wallet status, then Refresh from blockchain] "This status comes directly from the smart contract."

> [Account security] "Here I see where I am signed in, and which devices I used. I can sign out any of them.
> I can also turn on a second step with an authenticator app."

> [Switch accounts in MetaMask] "If I change the wallet, I am signed out at once."

### Part 3 — The admin

> [Login as admin] "Now I am the admin. I have extra pages: Admin, Wallets, Security and Controls."

> [Admin dashboard] "These are the statistics: users, logins, failures and blocked attacks."

> [Wallets, then Revoke, then Revoke wallet, then sign] "I revoke the user. It is written to the blockchain
> immediately, and MetaMask asked me to sign a message naming this exact wallet."

> [Switch to the user and try to log in] "The user is now refused, because the blockchain says the wallet is
> revoked."

> [As admin: Activate, then Request reactivation, then Controls → Waiting actions] "Unblocking is not
> immediate. It waits, and any admin can cancel it. The smart contract also enforces this wait."

> [Security, three tabs] "Here the admin sees every blocked attack, every event, and the trust score of
> every login."

> [Controls] "The admin also has an emergency stop, suspicious-wallet flags, and read-only API keys."

### Part 4 — The new features

> "On top of the original plan, we added these features:
> - account security with sessions, devices and new-sign-in alerts;
> - a second step with authenticator codes;
> - a trust score for every login;
> - fresh signatures for every important change;
> - an emergency stop;
> - delayed reactivations that can be cancelled;
> - suspicious-wallet flags and read-only API keys.
>
> Most recently, we redesigned the smart contract with two keys and a waiting time on the blockchain, so one
> stolen server key cannot give access back instantly. The server also checks the blockchain every 15
> seconds. We tested 30 attacks and wrote the results in our security report.
>
> In the latest round we added an anomaly model to the trust score, optional e-mail alerts, optional Redis so
> several servers can share sessions, automated tests for the website and fuzz tests for the contract, and a
> CI pipeline."

### Part 5 — Closing

> "So BlockAuth has no passwords, it uses the blockchain as the authority, and it protects both users and
> admins with fresh signatures, waiting times and monitoring. Thank you, Mam. We are happy to answer your
> questions."

---

## 6. Things That Are Not Available (be honest if asked)

| Not available | Simple answer |
|---|---|
| SMS or push alerts | "Alerts are shown in the app and, if the user adds an address, by e-mail." |
| Recovery of a lost wallet key | "There is no recovery. Only the owner has the key." |
| Making a user an admin from the website | "Admins come from the server's settings when they register." |
| Downloadable reports (PDF or Excel) | "Admins see the data on the pages; scripts can read it with API keys." |
| A QR code for the authenticator | "The key is typed into the app by hand." |
| Mobile phones | "It needs the MetaMask extension in a desktop browser." |
| A public blockchain | "It runs on a local Hardhat blockchain. It is prepared for the Sepolia test network, but not deployed there yet." |
| Guardian buttons on the website | "The guardian key is kept offline; its actions run from a script." |
| Date filters on the event pages | "The server supports them, but the page does not show them yet." |

---

## 7. Quick Answers if Mam Asks "Why?"

| Question | Answer |
|---|---|
| Why no password? | "Passwords can be guessed and stolen. A wallet signature cannot be guessed." |
| Why a blockchain? | "So the list of allowed wallets cannot be changed just by editing our database." |
| Why does each message work only once? | "So nobody can copy a signature and use it again." |
| Why do admin actions need a new signature? | "So a stolen session cookie is not enough to do damage." |
| Why must reactivation wait? | "Giving access back is risky, so other admins get time to cancel it." |
| Why two keys in the contract? | "So one stolen key cannot control everything." |
| Why can a normal user not see the admin pages? | "The server checks the role on every request, not just the menu." |

---

## 8. How to Explain the Risk Model (ready to speak)

- **What it is:** fixed rules (new device, new network, flags, failures, shared devices) give the trust score;
  next to them an unsupervised anomaly model, an Isolation Forest, learns what ordinary logins look like and
  rates how unusual a new one is, from 0.5 (ordinary) to 1 (very unusual).
- **How to demonstrate it:** as admin, open **Security → Sign-in risk**. Show the rule score, the anomaly score,
  the final score, the two features listed under "Unusual in", and the model card (mode "Shadow").
- **What we should say to Mam:** "The wallet signature is still the only proof of identity. After it, fixed rules
  give a trust score, and a small machine-learning model says how unusual the login looks and why. By default the
  model only advises: the admin sees its score, but it changes nothing. If we switch it on, it can only lower the
  score, by at most 30 points, never raise it, and if it fails the rules decide alone. We tested it on made-up
  data only, so we do not claim real-world accuracy."
- **If asked "is this AI?":** "Yes, a small unsupervised model, written in plain Java and trained on our own login
  records (or on made-up ordinary logins until we have 200 real ones). It is a risk indicator, never proof of
  identity." Details: `docs/RISK_MODEL.md`.
