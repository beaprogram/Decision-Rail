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

export interface Identity {
  authenticated: boolean;
  username: string | null;
  roles: string[];
  capabilities: Capabilities;
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
