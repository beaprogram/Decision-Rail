/** Response shapes from the browser API, mirroring the backend's records. */

export type PaymentStatus = 'AUTHORIZED' | 'CAPTURED' | 'VOIDED' | 'DECLINED' | 'REVIEW';
export type RiskOutcome = 'APPROVE' | 'REVIEW' | 'DECLINE';
export type Currency = 'CAD' | 'USD';

export interface Capabilities {
  viewPayments: boolean;
  createPayments: boolean;
  viewAccounts: boolean;
  viewPolicies: boolean;
  registerPolicies: boolean;
  viewReplay: boolean;
  createReplay: boolean;
  viewShadowComparisons: boolean;
  administerDelivery: boolean;
  configureShadow: boolean;
  viewMetrics: boolean;
}

/**
 * How the public portfolio instance introduces itself. Present only on that deployment.
 *
 * The visitor credential is public by design - a shared synthetic merchant - and the server bounds
 * what it can do. Nothing about any private identity is ever carried here.
 */
export interface PublicDemo {
  visitorUsername: string;
  visitorPassword: string;
  sharedState: boolean;
  commandsPerMinute: number;
  maxPaymentsPerAccount: number;
}

export interface Identity {
  authenticated: boolean;
  username: string | null;
  roles: string[];
  capabilities: Capabilities;
  /** Null or absent except on the public demo instance. */
  publicDemo?: PublicDemo | null;
}

export interface ReasonContribution {
  code: string;
  description: string;
  scoreContribution: number;
}

/** The stored risk decision. Separate from the payment's financial status by design. */
export interface Decision {
  outcome: RiskOutcome;
  score: number;
  ruleSetVersion: string;
  reasons: ReasonContribution[];
  flags: string[];
}

export interface Payment {
  id: string;
  accountId: string;
  amountMinor: number;
  currency: Currency;
  country: string;
  status: PaymentStatus;
  decision: Decision;
  failureCode: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PaymentSummary {
  id: string;
  accountId: string;
  amountMinor: number;
  currency: Currency;
  country: string;
  status: PaymentStatus;
  riskOutcome: RiskOutcome;
  riskScore: number;
  policyVersion: string;
  failureCode: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PaymentSearchPage {
  payments: PaymentSummary[];
  nextCursor: string | null;
  matchedCount: number;
  matchedCountCapped: boolean;
  matchedCountLimit: number;
}

export interface Account {
  id: string;
  currency: Currency;
  balanceMinor: number;
  heldMinor: number;
  availableMinor: number;
}

export interface LedgerEntry {
  id: string;
  journalId: string;
  ledgerAccount: string;
  side: 'DEBIT' | 'CREDIT';
  amountMinor: number;
  currency: Currency;
}

export interface TimelineCommand {
  action: string;
  occurredAt: string;
}

export interface TimelineConsumer {
  consumerGroup: string;
  consumedAt: string;
}

export interface TimelineEvent {
  eventId: string;
  sequence: number;
  eventType: string;
  committedAt: string;
  deliveryStatus: 'PENDING' | 'CLAIMED' | 'PUBLISHED' | 'FAILED';
  publishedAt: string | null;
  attempts: number;
  brokerPartition: number | null;
  brokerOffset: number | null;
  lastFailureKind: string | null;
  consumers: TimelineConsumer[];
}

export interface TimelineProjection {
  lastStatus: string;
  lastEventType: string;
  lastSequence: number;
  appliedEventCount: number;
  firstEventAt: string;
  lastEventAt: string;
}

export interface PaymentTimeline {
  paymentId: string;
  accountId: string;
  status: PaymentStatus;
  createdAt: string;
  updatedAt: string;
  commands: TimelineCommand[];
  events: TimelineEvent[];
  projection: TimelineProjection | null;
}

export interface PolicyVersion {
  versionId: string;
  /** Canonical JSON text. Parse it; it is not an embedded object. */
  definition: string;
  definitionHash: string;
  origin: 'BUILTIN' | 'CANDIDATE';
  ruleCount: number;
  createdBy: string;
  createdAt: string;
}

/** The parsed shape of a policy definition's rules. */
export interface PolicyRule {
  code: string;
  description: string;
  scoreContribution: number;
  flag: string;
  terminal: boolean;
  expression: unknown;
}

export interface ReplayJob {
  id: string;
  merchantId: string;
  candidateVersion: string;
  candidateHash: string;
  baselineSource: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  inputCount: number;
  completedCount: number;
  failedCount: number;
  pendingCount: number;
  approveCount: number;
  reviewCount: number;
  declineCount: number;
  divergenceCount: number;
  evaluationNanosTotal: number;
  windowFrom: string | null;
  windowTo: string;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  failureDetail: string | null;
}

export interface ReplayReport {
  jobId: string;
  merchantId: string;
  status: ReplayJob['status'];
  candidateVersion: string;
  candidateHash: string;
  baselinePolicyVersion: string | null;
  baselineSource: string;
  inputCount: number;
  completedCount: number;
  failedCount: number;
  pendingCount: number;
  candidateOutcomeCounts: Record<string, number>;
  baselineOutcomeCounts: Record<string, number>;
  divergenceCount: number;
  divergenceDenominator: string;
  /** Null when nothing has been evaluated yet. Never treat null as zero. */
  divergenceRate: number | null;
  evaluationTimings: { totalNanos: number; meanNanos: number | null; measuredEvaluations: number };
  timingMethod: string;
  labelledOutcomeDataAvailable: boolean;
  windowFrom: string | null;
  windowTo: string;
  createdAt: string;
  completedAt: string | null;
}

export interface ReplayResult {
  paymentId: string;
  baselineOutcome: RiskOutcome;
  baselineScore: number;
  baselineReasons: ReasonContribution[];
  candidateOutcome: RiskOutcome | null;
  candidateScore: number | null;
  candidateRawScore: number | null;
  candidateScoreCapped: boolean;
  candidateReasons: ReasonContribution[] | null;
  diverged: boolean;
  evaluationNanos: number;
  errorCode: string | null;
  paymentStatus: string;
  paymentFailureCode: string | null;
}

export interface ShadowComparison {
  paymentId: string;
  candidateVersion: string;
  baselineOutcome: RiskOutcome;
  baselineScore: number;
  baselineReasons: ReasonContribution[];
  candidateOutcome: RiskOutcome | null;
  candidateScore: number | null;
  candidateRawScore: number | null;
  candidateScoreCapped: boolean;
  candidateReasons: ReasonContribution[] | null;
  diverged: boolean;
  evaluationNanos: number;
  errorCode: string | null;
  evaluatedAt: string;
}

export interface ShadowSettings {
  enabled: boolean;
  candidateVersion: string | null;
  candidateHash: string | null;
  updatedBy: string;
  updatedAt: string;
  pendingTasks: number;
  failedTasks: number;
  comparisons: number;
  divergences: number;
}

export interface DeliveryStatus {
  liveness: string;
  readiness: string;
  asyncDelivery: string;
  breakerState: 'CLOSED' | 'HALF_OPEN' | 'OPEN';
  countsByStatus: Record<string, number>;
  oldestPendingAt: string | null;
  oldestPendingAgeSeconds: number;
  blockedPaymentCount: number;
}

export interface FailedEvent {
  eventId: string;
  paymentId: string;
  merchantId: string;
  eventType: string;
  aggregateSequence: number;
  attempts: number;
  occurredAt: string;
  lastAttemptAt: string | null;
  lastError: string | null;
  blocksLaterEvents: boolean;
}

export interface FailedEventPage {
  events: FailedEvent[];
  offset: number;
  limit: number;
  hasMore: boolean;
  totalFailed: number;
}

export interface RedriveResult {
  redrivenCount: number;
  redrivenEventIds: string[];
  stillBlockedPaymentCount: number;
  remainingFailedCount: number;
}

// ----- returns -----

export type ReturnType = 'REFUND' | 'REVERSAL';

/** One committed return operation, with the compensating journal that recorded it. */
export interface PaymentReturn {
  id: string;
  paymentId: string;
  accountId: string;
  returnType: ReturnType;
  amountMinor: number;
  currency: Currency;
  reason: string | null;
  sequenceNumber: number;
  journalId: string | null;
  createdAt: string;
}

/**
 * What may still be returned on one payment, decided by the server.
 *
 * `refundable` and `reversible` are not re-derived in the browser. Reimplementing a financial rule
 * here would put a second copy of it in a place that cannot enforce anything, and the two would drift.
 */
export interface PaymentReturns {
  paymentId: string;
  accountId: string;
  status: PaymentStatus;
  currency: Currency;
  capturedAmountMinor: number | null;
  returnedAmountMinor: number;
  remainingRefundableMinor: number;
  refundable: boolean;
  reversible: boolean;
  unavailableReason: 'NOT_CAPTURED' | 'FULLY_RETURNED' | 'PARTIALLY_RETURNED' | null;
  /** How many return operations this payment has in total. `returns` is one page and is usually shorter. */
  returnCount: number;
  /** One page of history, newest first. */
  returns: PaymentReturn[];
  /** Pass back as `cursor` for the next, older page. Null when this page is the last. */
  nextCursor: string | null;
  pageLimit: number;
}

/**
 * The receipt for one return command.
 *
 * The totals are those at the moment it committed, not current ones, so a replayed key keeps
 * describing its own operation rather than the payment's later state.
 */
export interface ReturnReceipt {
  returnId: string;
  paymentId: string;
  accountId: string;
  returnType: ReturnType;
  amountMinor: number;
  currency: Currency;
  reason: string | null;
  sequenceNumber: number;
  journalId: string;
  capturedAmountMinor: number;
  returnedAmountMinor: number;
  remainingRefundableMinor: number;
  createdAt: string;
}

// ----- reconciliation -----

export type ReconciliationStatus =
  | 'CLEAN'
  | 'DISCREPANCIES_FOUND'
  | 'INCOMPLETE'
  | 'INCOMPLETE_WITH_DISCREPANCIES';

export interface ReconciliationReference {
  kind: string;
  id: string;
}

export interface ReconciliationFinding {
  type: string;
  severity: 'CRITICAL' | 'WARNING';
  resourceType: string;
  resourceId: string;
  currency: Currency | null;
  expectedMinor: number | null;
  actualMinor: number | null;
  deltaMinor: number | null;
  detail: string;
  references: ReconciliationReference[];
}

export interface ReconciliationScope {
  accountFilter: string | null;
  accountLimit: number;
  paymentLimit: number;
  accountsExamined: number;
  paymentsExamined: number;
  returnsExamined: number;
  currencies: Currency[];
  snapshot: string;
  complete: boolean;
  incompleteReason: string | null;
  checks: string[];
}

export interface ReconciliationReport {
  merchantId: string;
  generatedAt: string;
  status: ReconciliationStatus;
  scope: ReconciliationScope;
  findings: ReconciliationFinding[];
  limitations: string[];
}
