# The sign-in risk model

BlockAuth scores every sign-in whose wallet signature checked out. Two parts, in this order:

1. **Rules (the authoritative floor).** `risk/TrustPolicy` starts at 100 and subtracts a fixed penalty for each
   rule that applies: new device, new network, flagged wallet, repeated failures from the same address, device
   shared with a flagged wallet. Pure, explainable, fail-closed. At 90 or more the signature is enough; from 50
   to 89 the second step is asked for when the wallet has one (and the signature alone is enough when it has
   none); below 50 a wallet without a second step is refused. All values are configurable (`TRUST_*`).
2. **An unsupervised anomaly model.** An Isolation Forest (`risk/anomaly/IsolationForest.java`, pure Java, no
   service, no ML library) rates how unusual a sign-in is compared with this system's ordinary sign-ins: about
   0.5 is ordinary, 1 is a clear outlier.

It is a **risk indicator, never proof of identity**. Only the wallet signature proves who signs in.

## Modes (`ANOMALY_MODE`)

| Mode | Effect |
|---|---|
| `off` | Not trained, not consulted. Features are still stored. |
| `shadow` (default) | Computed and stored for every sign-in and shown to administrators; **never changes a decision**. |
| `enforce` | Lowers the rule score by `round(maxPenalty × clamp((a − t) / (1 − t)))` (defaults t = 0.6, maxPenalty = 30) and adds the reason `ANOMALY_DETECTED`. It can only lower a score, never raise it; the decision thresholds stay the same. |

Any model failure (no model yet, a corrupt model, an exception) means "model unavailable": the rules decide
alone. The model makes no network or database call during sign-in; scoring with the explanation takes
p95 0.085 ms (measured, `AnomalyModelTest`).

## Features (15 numbers, no identifiers)

Hour of day (sine and cosine, UTC), day of week, new device, new network, failed or blocked attempts from this
address in the last hour (log), minutes since the wallet's last sign-in (log), sign-ins in 24 h, distinct
addresses in 7 days, distinct devices in 30 days, has a second step, wallet age in days (log), flagged,
closeness to a flagged wallet through shared devices, browser/system family changed since the last session.
They come from the same single database query as the rules. Stored per assessment as a JSON object of numbers:
never an IP address or user agent.

## Training

* At startup when no model exists, then nightly (`ANOMALY_TRAINING_SCHEDULE`). A PostgreSQL advisory lock lets
  only one instance train; every instance picks up the newest stored model within 5 minutes.
* **Cold start:** until 200 suitable real sign-ins exist (`ANOMALY_MIN_REAL_SAMPLES`), the model is trained on a
  built-in synthetic population of ordinary sign-ins (`SyntheticSignIns.java`), which encodes assumptions
  (daytime use, mostly known devices) rather than observations.
* **Real data:** only sign-ins that were allowed, of wallets that are not flagged and not linked to a flagged
  wallet, at most 50 per wallet (`ANOMALY_PER_WALLET_CAP`), so one wallet cannot shape the model (poisoning
  resistance).
* A new model is built off to the side, stored with a new version (`risk_models`), and swapped in atomically.
  No endpoint changes or retrains the model; `GET /api/admin/risk-model` is read-only.

## Explanation

For each sign-in the two features whose replacement by their training median lowers the anomaly score most are
stored and shown ("Unusual in: new device, hour").

## Evaluation (synthetic data only)

`AnomalyEvaluationTest`: a held-out synthetic benign population and four made-up attacks, 2000 each, threshold
0.6, measured on 2026-11-23:

| Group | Flagged as unusual | AUC vs. benign |
|---|---|---|
| Ordinary sign-ins (false positives) | 2.85 % | — |
| New device and new network at an unusual hour | 100 % | 1.000 |
| Failure burst, then success | 23.5 % | 0.936 |
| Many distinct addresses for one wallet | 13.25 % | 0.908 |
| Device shared with a flagged wallet | 100 % | 0.995 |

**These numbers describe synthetic data, not real-world accuracy.** An Isolation Forest picks split features at
random, so an outlier in only one or two of fifteen features (a failure burst, many addresses) is ranked high
(AUC above 0.9) but often stays below the threshold; the rules (`REPEATED_FAILURES`) remain the main defence
there. The model never sees a flag link in training (flagged wallets are excluded), so the shared-device case
is caught by its side effects (new device, other browser) and by the `LINKED_TO_FLAGGED` rule. Before switching
to `enforce`, run in `shadow` on real traffic and review the stored scores.

## How to explain the risk model (script)

> "Each sign-in first needs a valid wallet signature; that is the proof of identity. After that we compute a
> trust score from fixed rules: a new device costs 30 points, a new network 20, and so on. Next to these rules we
> added a small unsupervised anomaly model, an Isolation Forest, that learns what ordinary sign-ins look like in
> our own records and says how unusual a new one is. By default it runs in shadow mode: administrators see its
> score and the two features that made a sign-in unusual, but it changes nothing. In enforce mode it can only
> lower the score, by at most 30 points, never raise it. If the model fails, the rules decide alone. We measured
> it on synthetic data only, so we do not claim real-world accuracy: it is a risk indicator, not proof of
> identity."
