package com.graalmail.p2p;

public record SendResult(String messageId, String transactionHash, DeliveryState state) {}