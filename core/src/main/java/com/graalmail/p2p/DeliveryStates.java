package com.graalmail.p2p;

/**
 * User-facing labels for delivery states (spec section 15.2). The UI must
 * distinguish every state and must never label an email "Delivered" when only
 * the blockchain notification has been confirmed.
 */
public final class DeliveryStates {
    private DeliveryStates() {}

    public static String label(DeliveryState state) {
        return switch (state) {
            case DRAFT -> "Draft";
            case QUEUED -> "Queued";
            case UPLOADING -> "Uploading encrypted content";
            case STORED -> "Waiting for storage confirmation";
            case NOTIFICATION_SUBMITTED -> "Publishing Polygon notification";
            case NOTIFICATION_CONFIRMED -> "Notification confirmed";
            case WAITING_RETRIEVAL -> "Waiting for recipient retrieval";
            case DELIVERED -> "Delivered";
            case ACKNOWLEDGED -> "Delivered and acknowledged";
            case FAILED -> "Failed";
            case EXPIRED -> "Expired";
        };
    }

    /** Terminal states need no further background work. */
    public static boolean isTerminal(DeliveryState state) {
        return state == DeliveryState.DELIVERED || state == DeliveryState.ACKNOWLEDGED
                || state == DeliveryState.FAILED || state == DeliveryState.EXPIRED;
    }
}
