package com.graalmail.p2p;

public enum DeliveryState {
    DRAFT,
    QUEUED,
    UPLOADING,
    STORED,
    NOTIFICATION_SUBMITTED,
    NOTIFICATION_CONFIRMED,
    WAITING_RETRIEVAL,
    DELIVERED,
    ACKNOWLEDGED,
    FAILED,
    EXPIRED
}