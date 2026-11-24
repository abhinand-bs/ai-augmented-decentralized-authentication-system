# End-to-end tests (Playwright, Chromium)

`window.ethereum` is replaced by a stub (`wallet.js`) whose `personal_sign` signs for real, in Node, with a test
key derived from a label (`ethers` is a dev dependency only; the production bundle contains no web3 library,
checked in CI). Install the browser once: `npx playwright install chromium`.

## UI layer (default): `npm run test:e2e`

Builds the app, serves it with `vite preview` on port 4173 and answers every `/api` call from `mockApi.js`
(sessions, challenges, real signature checks with `ethers.verifyMessage`, injectable status codes and delays).
`SCREENSHOTS=1 npm run test:e2e` also saves full-page screenshots of every page at 360, 768, 1280 and 1920 px to
`e2e-screenshots/`.

## Full-stack smoke: `E2E_FULLSTACK=1 npm run test:e2e`

Needs a Hardhat node with the contract deployed, a backend whose `BLOCKAUTH_ADMIN_WALLETS` contains the e2e
admin address `0x75Da82a28Cb60c19D19f8dC40DB4752fBc08fE4B` (derived from the label "admin") and
`APP_ORIGIN=http://localhost:5173`, and the dev server (started by Playwright). Point the dev server at a
backend on another port with `BACKEND_URL=http://localhost:18080`.

PowerShell:

    $env:E2E_FULLSTACK = '1'; $env:BACKEND_URL = 'http://localhost:18080'; npm run test:e2e

Bash:

    E2E_FULLSTACK=1 BACKEND_URL=http://localhost:18080 npm run test:e2e

The test runs five sign-in challenges from 127.0.0.1. The backend allows ten a minute per address, so run it at
most twice a minute; it ends with one deliberately refused sign-in, and from the third failure within an hour
the trust score counts "repeated failures" for that address. Use a fresh database for repeated runs, or keep the
administrator's device file in `e2e/.state/` (the test does this for you).

## Live run against the real stack: `E2E_LIVE=1 npx playwright test`

`live.spec.js` drives every risk scenario and the admin features against real services; results go to
`live-results/live-results.jsonl`, screenshots to `../BlockAuth-Live-Test-Screenshots/`. It needs:

- backend A on 8080 and backend B on 18081, both with the `redis` profile, the same Redis and the **test** database
  (never the main one), mail to Mailpit (127.0.0.1:1025, web 8025), `TRUSTED_PROXIES=127.0.0.1/32, ::1/128`,
  `SERVER_FORWARD_HEADERS_STRATEGY=native`, `ADMIN_ACTION_DELAY=10m` and the e2e admin wallet above;
- the dev server on 5173 → 8080 and a second one on 5174 → 18081
  (PowerShell: `$env:BACKEND_URL='http://localhost:18081'; npx vite --port 5174`;
  Bash: `BACKEND_URL=http://localhost:18081 npx vite --port 5174`);
- a Hardhat node with the contract deployed.

Run it (PowerShell): `$env:E2E_LIVE='1'; npx playwright test`; add `$env:LIVE_PHASE='lift'` etc. for the other phases.
Bash: `E2E_LIVE=1 LIVE_PHASE=lift npx playwright test`.

Phases (`LIVE_PHASE`): `main` (default; ends with the emergency stop on and its lift requested), `lift` (after the
delay: checks the lift was carried out, removes leftover live-test flags), `pages` (remaining routes and guards),
`enforce` (backend A restarted with `ANOMALY_MODE=enforce ANOMALY_THRESHOLD=0.5`). Client networks are simulated
with `X-Forwarded-For` from the documentation ranges, with a different last octet each run, because failures count
per address for an hour.
