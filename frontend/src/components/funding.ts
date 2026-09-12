import type { PaymentStatus, RiskOutcome } from '../api/types';

/**
 * What actually happened to the money, derived from lifecycle state rather than guessed.
 *
 * The absence of a failure code does not mean funds were reserved. A policy decline and a payment
 * still under review both reserve nothing and both have no failure code, so reading "Funds reserved"
 * off a missing code told an operator the opposite of the truth. A captured payment is the mirror
 * image: its hold is gone precisely because the capture consumed it, so describing the hold as still
 * active is equally wrong.
 *
 * Risk and funding stay separate throughout. An APPROVE risk decision never implies a successful hold,
 * and an insufficient-funds decline never implies the policy refused anything.
 */
export interface FundingPresentation {
  tone: 'success' | 'warning' | 'danger' | 'neutral';
  label: string;
  note: string;
  /** True when funds are reserved right now and could still be captured or released. */
  holdActive: boolean;
}

export function fundingPresentation(
  status: PaymentStatus,
  failureCode: string | null,
  riskOutcome: RiskOutcome,
): FundingPresentation {
  switch (status) {
    case 'AUTHORIZED':
      return {
        tone: 'success',
        label: 'Funds reserved',
        note: 'Held against the account until this payment is captured or voided.',
        holdActive: true,
      };
    case 'CAPTURED':
      return {
        tone: 'success',
        label: 'Funds captured',
        // The hold is gone, not still standing: saying otherwise would double-count the money.
        note: 'The authorization hold was consumed and the balance reduced.',
        holdActive: false,
      };
    case 'VOIDED':
      return {
        tone: 'neutral',
        label: 'Hold released',
        note: 'The reservation was returned to available funds. Nothing was captured.',
        holdActive: false,
      };
    case 'REVIEW':
      return {
        tone: 'warning',
        label: 'No funds reserved',
        note: 'The policy asked for review, so this authorization reserved nothing.',
        holdActive: false,
      };
    case 'DECLINED':
      if (failureCode) {
        return {
          tone: 'danger',
          label: failureCode,
          note:
            riskOutcome === 'APPROVE'
              ? 'The policy approved this payment. It was declined because the account did not have enough available funds, so nothing was reserved.'
              : 'Nothing was reserved.',
          holdActive: false,
        };
      }
      return {
        tone: 'danger',
        label: 'No funds reserved',
        note: 'Declined by policy before any reservation was attempted.',
        holdActive: false,
      };
  }
}
