package com.graalmail.p2p.blockchain;

import com.graalmail.core.error.ErrorCode;
import com.graalmail.p2p.PolyMailException;

/**
 * Anti-spam policy for notification publishing (spec section 5.5).
 *
 * <p>First release: open publishing with off-chain filtering (see
 * {@code SenderAllowlist}); this policy stays {@link #disabled} by default, as
 * the spec defers token economics until the basic lifecycle is reliable. When
 * an operator enables deposits, senders attach the funding transaction hash as
 * proof and mailbox/scanner operators verify it against their own policy.
 */
public record DepositPolicy(boolean required, String amountWei, String recipient) {
    public static DepositPolicy disabled() {
        return new DepositPolicy(false, "0", "");
    }

    public static DepositPolicy required(String amountWei, String recipient) {
        if (amountWei == null || recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException("amount and recipient required");
        }
        return new DepositPolicy(true, amountWei, recipient);
    }

    /** Validates the deposit proof carried by an outgoing send. */
    public void checkSend(String depositTxProof) {
        if (!required) return;
        if (depositTxProof == null || depositTxProof.isBlank()) {
            throw new PolyMailException(ErrorCode.PERMISSION_DENIED,
                    "notification requires a spam-deposit transaction proof", false, true, false);
        }
    }
}
