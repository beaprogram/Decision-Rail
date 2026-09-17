# Walkthrough recording

Two recordings of the working application in its deployed configuration - the public-demo mode
behind Caddy over HTTPS, the same image and Compose file the public instance uses - driven through
the real browser by [frontend/walkthrough/walkthrough.spec.ts](../frontend/walkthrough/walkthrough.spec.ts)
and captured by Playwright. Nothing in them is mocked or staged: every screen shows data the service
produced, and the live authorization, capture and refund in the first recording are real commands
against the real transaction boundary.

Recorded **2026-09-17** on the rehearsal stack described in [verification.md](verification.md), from
the tree released as **`v0.10.0`** (revision `1977084`). The corrective release `v0.10.1` changed no
visible behaviour - its fixes are in the limiter, the edge, health authorization, budgeting, replay
admission, broker storage and the operator scripts - so the recordings were not re-made; they show
the `v0.10.0` build and are labelled as such. The videos are attached to
[the v0.10.0 release](https://github.com/beaprogram/Decision-Rail/releases/tag/v0.10.0) rather than
committed, so the repository stays small:

- [**`visitor-walkthrough.webm`**](https://github.com/beaprogram/Decision-Rail/releases/download/v0.10.0/visitor-walkthrough.webm)
  (about 56 s) - what anyone can do with the public credentials.
- [**`operator-walkthrough.webm`**](https://github.com/beaprogram/Decision-Rail/releases/download/v0.10.0/operator-walkthrough.webm)
  (about 19 s) - **operator segment, recorded against an isolated instance with a private
  administrator identity.** A visitor to the public demo cannot perform these actions; that is the
  point of showing them separately.

No credential appears in either. The visitor's is entered by the page's own button and masked; the
administrator's is filled without being displayed.

To re-record against any public-mode instance:

```bash
cd frontend
VISITOR_PASSWORD=… ADMIN_PASSWORD=… WALKTHROUGH_BASE_URL=https://<host> \
  npx playwright test --config playwright.walkthrough.config.ts
# videos land under frontend/walkthrough-output/<test>/video.webm
```

## Script

### Visitor

| Time | On screen | What it shows |
| --- | --- | --- |
| 0:00 | Sign-in page with the *Public demo* notice; *Use the visitor credentials*; *Sign in* | The demo introduces itself: synthetic money, shared merchant, the limits. The credential is public by design. |
| 0:06 | **Payments** with the seeded history; the sidebar reads *Public demo · synthetic money · shared merchant* | Every row was produced by the service - approvals, a review, declines, a capture, a void, a refund, a reversal. |
| 0:10 | Filter *Payment status = DECLINED, Currency = CAD*, *Apply*; open the row | **Stored decision**: outcome `DECLINE`, the rule `TEST_COUNTRY_BLOCKED` and its score contribution, the policy version. The decision is stored with the payment, not recomputed. |
| 0:17 | *Payments* filtered to `DECLINED` in USD; open the row | *Declined for funds, not by policy*: the risk decision was `APPROVE`, the failure code `INSUFFICIENT_FUNDS`, nothing reserved. A funding outcome and a risk decision are kept apart. |
| 0:23 | **New authorization**: second CAD account, 42.00, *Review and authorize*, *Authorize*; *Open payment* | A live command through the same idempotent path the API uses. `APPROVE`, `AUTHORIZED`, funds held. |
| 0:31 | *Capture*, *Capture funds* | *Capture completed*; the **Capture journal** card: one balanced debit and credit, sealed. |
| 0:35 | Returns card: refund 15.00, reason *customer returned one item*, *Refund*, *Return the funds* | *Refund recorded*: a return operation with its reason, its own compensating journal, and the captured / returned / remaining figures re-read from the server. |
| 0:41 | **Reconciliation**, *Reconcile* | *Everything in scope reconciles*: expected balances rebuilt from the ledger, the returned totals checked against the return operations, the scope and snapshot stated. Read-only. |
| 0:46 | **Policy replay**: the seeded `COMPLETED` job; open it; *Diverged only* | The visitor's history replayed against the stricter `strict-amount-v1`; each divergence shown with the baseline (stored) decision beside the candidate's. Not a benchmark, and it says so. |
| 0:52 | **Shadow comparisons** | The candidate evaluated alongside a live authorization: a divergence recorded as an observation, the live decision unchanged. |
| 0:55 | *Sign out* | The session is destroyed on the server. |

### Operator (isolated instance)

| Time | On screen | What it shows |
| --- | --- | --- |
| 0:00 | Sign in as `admin` (credential not shown) | A private identity. The sidebar shows administrative sections a visitor never sees - and never reaches, because each endpoint enforces the rule itself. |
| 0:03 | **Policy versions**; *Register candidate*; an invalid definition; *Register candidate* | *The definition was rejected* with the JSON path of the offending field. Candidates are content-addressed and never authoritative. |
| 0:10 | **Shadow configuration** | Selecting a candidate for shadow evaluation grants it no authority; the page says it does not backfill. |
| 0:14 | **Event delivery** | Published events, stalled payment streams, terminal failures, and the redrive control - the operational state of asynchronous delivery, separate from payment readiness. |
| 0:18 | *Sign out* | |
