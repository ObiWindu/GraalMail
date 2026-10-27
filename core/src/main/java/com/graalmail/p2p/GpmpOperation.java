package com.graalmail.p2p;

public enum GpmpOperation {
    HELLO,
    RESOLVE_MAILBOX,
    STORE_MESSAGE,
    STORE_RECEIPT,
    FETCH_MESSAGE,
    FETCH_RESULT,
    ACK_MESSAGE,
    DELETE_REQUEST,
    PEER_ANNOUNCE,
    HEARTBEAT,
    ERROR
}