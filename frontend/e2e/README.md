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
