package com.dasannn.socialblueprint.domain;

/**
 * State machine for pending refund compensation records per SB-057:
 * <ul>
 *   <li>{@link #INTENDED}: Recorded before withdrawal. Discarded if crash occurs before charge.</li>
 *   <li>{@link #CHARGED}: Withdrawal succeeded. Must be refunded if event commit fails or crash occurs.</li>
 *   <li>{@link #REFUNDING}: Claimed by an active refund pass. Prevents concurrent double-refunds.</li>
 *   <li>{@link #REFUNDED}: Refund deposit completed. Deleted asynchronously; ignored on restart.</li>
 *   <li>{@link #EVENT_WRITTEN}: Reputation event write committed. Deleted asynchronously; discarded on restart.</li>
 *   <li>{@link #UNCERTAIN}: Vault outcome is genuinely unknown. Left for manual operator review without guessing.</li>
 * </ul>
 */
public enum CompensationState {
    INTENDED,
    CHARGED,
    REFUNDING,
    REFUNDED,
    EVENT_WRITTEN,
    UNCERTAIN
}
