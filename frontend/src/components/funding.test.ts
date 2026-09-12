import { describe, expect, it } from 'vitest';
import { fundingPresentation } from './funding';

/**
 * The funding label must come from lifecycle state, not from the absence of a failure code.
 *
 * The defect these cover: a policy-declined payment reserved nothing, had no failure code, and was
 * therefore labelled "Funds reserved".
 */
describe('fundingPresentation', () => {
  it('reports a live hold only while the payment is authorized', () => {
    const authorized = fundingPresentation('AUTHORIZED', null, 'APPROVE');
    expect(authorized.label).toBe('Funds reserved');
    expect(authorized.holdActive).toBe(true);

    for (const status of ['CAPTURED', 'VOIDED', 'REVIEW', 'DECLINED'] as const) {
      expect(fundingPresentation(status, null, 'APPROVE').holdActive).toBe(false);
    }
  });

  it('does not describe a captured payment as still holding funds', () => {
    const captured = fundingPresentation('CAPTURED', null, 'APPROVE');
    expect(captured.label).toBe('Funds captured');
    expect(captured.note).not.toMatch(/held|reserved/i);
  });

  it('says a voided payment released its hold and captured nothing', () => {
    const voided = fundingPresentation('VOIDED', null, 'APPROVE');
    expect(voided.label).toBe('Hold released');
    expect(voided.note).toMatch(/nothing was captured/i);
  });

  it('never claims a reservation for a payment awaiting review', () => {
    const review = fundingPresentation('REVIEW', null, 'REVIEW');
    expect(review.label).toBe('No funds reserved');
    expect(review.tone).not.toBe('success');
  });

  it('never claims a reservation for a policy decline', () => {
    // The exact defect: no failure code, nothing reserved, previously labelled "Funds reserved".
    const declined = fundingPresentation('DECLINED', null, 'DECLINE');
    expect(declined.label).toBe('No funds reserved');
    expect(declined.label).not.toBe('Funds reserved');
    expect(declined.note).toMatch(/declined by policy/i);
  });

  it('shows the funding failure for an insufficient-funds decline and keeps the risk outcome separate', () => {
    const fundsDeclined = fundingPresentation('DECLINED', 'INSUFFICIENT_FUNDS', 'APPROVE');
    expect(fundsDeclined.label).toBe('INSUFFICIENT_FUNDS');
    // The explanation must say the policy approved it, because that is the correct and surprising part.
    expect(fundsDeclined.note).toMatch(/policy approved/i);
    expect(fundsDeclined.note).toMatch(/nothing was reserved/i);
  });

  it('does not infer a hold from an APPROVE risk outcome alone', () => {
    // Every non-authorized state with an approving risk decision still reports no live hold.
    for (const status of ['CAPTURED', 'VOIDED', 'REVIEW', 'DECLINED'] as const) {
      const presentation = fundingPresentation(status, null, 'APPROVE');
      expect(presentation.holdActive).toBe(false);
      expect(presentation.label).not.toBe('Funds reserved');
    }
  });
});
