/**
 * Exact conversion between a typed decimal amount and integer currency minor units.
 *
 * The backend stores money as integer minor units and nothing here is allowed to blur that.
 * Multiplying a parsed float by 100 is the usual way this goes wrong: `8.11 * 100` is
 * 811.0000000000001 and `1.005 * 100` is 100.49999999999999, so rounding turns some amounts into the
 * wrong number of cents. Every conversion below works on the digit string with BigInt, so there is no
 * floating-point step to round.
 */

/** Matches the backend's own bound on a payment amount. */
export const MAX_AMOUNT_MINOR = 1_000_000_000_000;
export const MINOR_UNIT_DIGITS = 2;

export type MoneyParseFailure =
  | 'EMPTY'
  | 'NOT_A_NUMBER'
  | 'TOO_MANY_DECIMALS'
  | 'NOT_POSITIVE'
  | 'ABOVE_MAXIMUM';

export type MoneyParseResult =
  | { ok: true; minorUnits: number }
  | { ok: false; reason: MoneyParseFailure; message: string };

// Plain decimal only. No sign, no exponent, no separators: an amount field is not an expression, and
// accepting "1e3" or "1,000" invites a different reading than the one displayed back.
const DECIMAL = /^(\d+)(?:\.(\d*))?$/;

/**
 * Parses what someone typed into integer minor units.
 *
 * Rejects rather than rounds. An amount with three decimal places is a mistake the person needs to
 * see, not something to silently turn into a different amount of money.
 */
export function parseMinorUnits(input: string): MoneyParseResult {
  const trimmed = input.trim();
  if (trimmed === '') {
    return { ok: false, reason: 'EMPTY', message: 'Enter an amount.' };
  }
  const match = DECIMAL.exec(trimmed);
  if (!match) {
    return {
      ok: false,
      reason: 'NOT_A_NUMBER',
      message: 'Enter a plain amount such as 25 or 25.00, without a sign or separators.',
    };
  }
  const whole = match[1] ?? '0';
  const fraction = match[2] ?? '';
  if (fraction.length > MINOR_UNIT_DIGITS) {
    return {
      ok: false,
      reason: 'TOO_MANY_DECIMALS',
      message: `Use at most ${MINOR_UNIT_DIGITS} decimal places.`,
    };
  }

  // Digit concatenation, so the value is exact by construction rather than by rounding.
  const padded = fraction.padEnd(MINOR_UNIT_DIGITS, '0');
  const minor = BigInt(whole + padded);

  if (minor <= 0n) {
    return { ok: false, reason: 'NOT_POSITIVE', message: 'Enter an amount greater than zero.' };
  }
  if (minor > BigInt(MAX_AMOUNT_MINOR)) {
    return {
      ok: false,
      reason: 'ABOVE_MAXIMUM',
      message: `The largest supported amount is ${formatMinorUnits(MAX_AMOUNT_MINOR)}.`,
    };
  }
  // The bound above sits well inside Number.MAX_SAFE_INTEGER, so this cannot lose precision. The
  // check stays because the guarantee should hold even if that bound is ever raised.
  const asNumber = Number(minor);
  if (!Number.isSafeInteger(asNumber)) {
    return { ok: false, reason: 'ABOVE_MAXIMUM', message: 'That amount is too large to handle exactly.' };
  }
  return { ok: true, minorUnits: asNumber };
}

/**
 * Renders integer minor units as a decimal string, exactly.
 *
 * Integer arithmetic only: dividing by 100 would reintroduce the imprecision the parser avoids.
 */
export function formatMinorUnits(minorUnits: number): string {
  const negative = minorUnits < 0;
  const digits = Math.abs(Math.trunc(minorUnits)).toString().padStart(MINOR_UNIT_DIGITS + 1, '0');
  const whole = digits.slice(0, digits.length - MINOR_UNIT_DIGITS);
  const fraction = digits.slice(digits.length - MINOR_UNIT_DIGITS);
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${negative ? '-' : ''}${grouped}.${fraction}`;
}

/**
 * Renders an amount with its currency.
 *
 * The currency code is always shown. CAD and USD are both dollars and both use `$`, so a bare symbol
 * would make two different amounts of money look like the same one.
 */
export function formatMoney(minorUnits: number, currency: string): string {
  return `${formatMinorUnits(minorUnits)} ${currency}`;
}
