import { describe, expect, it } from 'vitest';
import { MAX_AMOUNT_MINOR, formatMinorUnits, formatMoney, parseMinorUnits } from './money';

const minorUnitsOf = (input: string): number => {
  const result = parseMinorUnits(input);
  if (!result.ok) throw new Error(`expected ${input} to parse, got ${result.reason}`);
  return result.minorUnits;
};

const failureOf = (input: string): string => {
  const result = parseMinorUnits(input);
  if (result.ok) throw new Error(`expected ${input} to be rejected, got ${result.minorUnits}`);
  return result.reason;
};

describe('parseMinorUnits', () => {
  it('converts whole and fractional amounts exactly', () => {
    expect(minorUnitsOf('25')).toBe(2500);
    expect(minorUnitsOf('25.00')).toBe(2500);
    expect(minorUnitsOf('25.5')).toBe(2550);
    expect(minorUnitsOf('25.05')).toBe(2505);
    expect(minorUnitsOf('0.01')).toBe(1);
    expect(minorUnitsOf(' 1.99 ')).toBe(199);
    expect(minorUnitsOf('1000000.00')).toBe(100000000);
  });

  it('is exact for the amounts that floating point multiplication gets wrong', () => {
    // Each of these is a value where `parseFloat(x) * 100` is not the integer it should be, so the
    // usual parse-and-multiply approach produces the wrong number of cents after rounding.
    const brokenByFloatMath: Array<[string, number]> = [
      ['0.07', 7],
      ['0.14', 14],
      ['0.28', 28],
      ['0.29', 29],
      ['0.55', 55],
      ['0.57', 57],
      ['0.58', 58],
    ];
    for (const [typed, expected] of brokenByFloatMath) {
      // First show the naive conversion really is broken for this value, so the test is not just
      // restating the implementation.
      expect(Number.isInteger(parseFloat(typed) * 100)).toBe(false);
      // Then show this parser gets it exactly right.
      expect(minorUnitsOf(typed)).toBe(expected);
    }
    expect(minorUnitsOf('1234567.89')).toBe(123456789);
  });

  it('rejects more precision than a minor unit can hold instead of rounding it', () => {
    expect(failureOf('1.234')).toBe('TOO_MANY_DECIMALS');
    expect(failureOf('0.001')).toBe('TOO_MANY_DECIMALS');
    expect(failureOf('25.000')).toBe('TOO_MANY_DECIMALS');
  });

  it('rejects anything that is not a plain positive decimal', () => {
    expect(failureOf('')).toBe('EMPTY');
    expect(failureOf('   ')).toBe('EMPTY');
    expect(failureOf('abc')).toBe('NOT_A_NUMBER');
    expect(failureOf('-5.00')).toBe('NOT_A_NUMBER');
    expect(failureOf('+5.00')).toBe('NOT_A_NUMBER');
    expect(failureOf('1e3')).toBe('NOT_A_NUMBER');
    expect(failureOf('1,000')).toBe('NOT_A_NUMBER');
    expect(failureOf('25.00.00')).toBe('NOT_A_NUMBER');
    expect(failureOf('0x10')).toBe('NOT_A_NUMBER');
    expect(failureOf('Infinity')).toBe('NOT_A_NUMBER');
    expect(failureOf('NaN')).toBe('NOT_A_NUMBER');
  });

  it('rejects zero and amounts beyond the supported maximum', () => {
    expect(failureOf('0')).toBe('NOT_POSITIVE');
    expect(failureOf('0.00')).toBe('NOT_POSITIVE');
    // The maximum itself is accepted; one minor unit more is not.
    expect(minorUnitsOf('10000000000.00')).toBe(MAX_AMOUNT_MINOR);
    expect(failureOf('10000000000.01')).toBe('ABOVE_MAXIMUM');
    expect(failureOf('99999999999999999999')).toBe('ABOVE_MAXIMUM');
  });

  it('never produces a value outside the safe integer range', () => {
    const result = parseMinorUnits('10000000000.00');
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(Number.isSafeInteger(result.minorUnits)).toBe(true);
      expect(result.minorUnits).toBeLessThanOrEqual(Number.MAX_SAFE_INTEGER);
    }
  });
});

describe('formatMinorUnits', () => {
  it('renders minor units without dividing by a hundred', () => {
    expect(formatMinorUnits(2500)).toBe('25.00');
    expect(formatMinorUnits(1)).toBe('0.01');
    expect(formatMinorUnits(0)).toBe('0.00');
    expect(formatMinorUnits(7)).toBe('0.07');
    expect(formatMinorUnits(811)).toBe('8.11');
    expect(formatMinorUnits(123456789)).toBe('1,234,567.89');
    expect(formatMinorUnits(MAX_AMOUNT_MINOR)).toBe('10,000,000,000.00');
  });

  it('round-trips every parsed amount back to the same digits', () => {
    const cases: Array<[string, string]> = [
      ['0.01', '0.01'],
      ['0.07', '0.07'],
      ['1.99', '1.99'],
      ['8.11', '8.11'],
      ['25', '25.00'],
      ['25.5', '25.50'],
      ['1234567.89', '1,234,567.89'],
      ['10000000000.00', '10,000,000,000.00'],
    ];
    for (const [typed, displayed] of cases) {
      expect(formatMinorUnits(minorUnitsOf(typed))).toBe(displayed);
    }
  });

  it('always shows the currency code, because CAD and USD share a symbol', () => {
    expect(formatMoney(2500, 'CAD')).toBe('25.00 CAD');
    expect(formatMoney(2500, 'USD')).toBe('25.00 USD');
    expect(formatMoney(2500, 'CAD')).not.toBe(formatMoney(2500, 'USD'));
  });
});
