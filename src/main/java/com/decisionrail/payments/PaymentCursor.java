package com.decisionrail.payments;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Position in a payment search result, as the ordering key of the last row already returned.
 *
 * <p>Keyset rather than offset pagination. Payments are created continuously, so an offset shifts
 * under the reader: a row inserted during paging pushes everything down and the next page silently
 * skips a payment. A key is stable, and because the key is the full ordering tuple
 * {@code (createdAt, id)} it stays stable even when several payments share a timestamp.
 *
 * <p>Opaque to clients on purpose, so the ordering can change without becoming a contract, and
 * rejected rather than ignored when malformed, so a corrupted cursor cannot silently restart paging
 * from the beginning and duplicate rows the caller has already seen.
 */
public record PaymentCursor(Instant createdAt, UUID id) {

    public PaymentCursor {
        if (createdAt == null || id == null) {
            throw new IllegalArgumentException("a cursor needs both an instant and an id");
        }
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((createdAt.toString() + '|' + id).getBytes(StandardCharsets.UTF_8));
    }

    public static PaymentCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded.strip()), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf('|');
            if (separator < 0) throw new IllegalArgumentException("missing separator");
            return new PaymentCursor(Instant.parse(decoded.substring(0, separator)),
                    UUID.fromString(decoded.substring(separator + 1)));
        } catch (RuntimeException malformed) {
            throw new PaymentException("INVALID_SEARCH_CURSOR", 400,
                    "The pagination cursor is not valid. Start the search again without a cursor.");
        }
    }
}
