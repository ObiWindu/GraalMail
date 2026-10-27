package com.graalmail.p2p.blockchain;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface PolygonNotificationService {
    CompletionStage<String> publishNotification(NotificationRequest request);
    CompletionStage<List<NotificationEvent>> scan(BlockchainCursor from, BlockchainCursor to);
    CompletionStage<TransactionStatus> track(String transactionHash);
}