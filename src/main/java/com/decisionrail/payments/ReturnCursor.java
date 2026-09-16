package com.decisionrail.payments;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Position in one payment's return history, as the per-payment sequence number of the last row
 * already returned.
 *
 * <p>Keyset rather than offset, for the same reason payment search is: a return committing while
 * someone is paging would shift an offset and make the next page skip an operation. The sequence
 * number is assigned inside the financial transaction and is dense per payment, so it is a total
 * order that nothing later can disturb. History is walked newest first, so a return committed
 * mid-paging appears ahead of the pages already fetched rather than in the middle of them.
 *
 * <p>The payment id travels inside the cursor and is checked against the payment being read. Ownership
 * is enforced by the query either way - the merchant comes from authentication and is always in the
 * predicate - but a cursor minted for a different payment is a mistake, and being told so is better
 * than being silently repositioned inside a payment it was not describing.
 */
public record ReturnCursor(UUID paymentId, int sequenceNumber) {

    public ReturnCursor {
        if (paymentId == null) throw new IllegalArgumentException("a cursor needs the payment it belongs to");
        if (sequenceNumber <= 0) throw new IllegalArgumentException("a cursor needs a positive sequence number");
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((paymentId + "|" + sequenceNumber).getBytes(StandardCharsets.UTF_8));
    }

    /** @return the decoded cursor, or null when none was supplied. */
    public static ReturnCursor decode(String encoded, UUID expectedPayment) {
        if (encoded == null || encoded.isBlank()) return null;
        ReturnCursor cursor;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded.strip()), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf('|');
            if (separator < 0) throw new IllegalArgumentException("missing separator");
            cursor = new ReturnCursor(UUID.fromString(decoded.substring(0, separator)),
                    Integer.parseInt(decoded.substring(separator + 1)));
        } catch (RuntimeException malformed) {
            throw new PaymentException("INVALID_RETURN_CURSOR", 400,
                    "The return history cursor is not valid. Read the history again without a cursor.");
        }
        if (!cursor.paymentId().equals(expectedPayment)) {
            throw new PaymentException("INVALID_RETURN_CURSOR", 400,
                    "This cursor belongs to a different payment's return history.");
        }
        return cursor;
    }
}
