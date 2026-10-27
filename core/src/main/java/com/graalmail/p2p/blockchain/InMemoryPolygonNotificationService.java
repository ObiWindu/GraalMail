package com.graalmail.p2p.blockchain;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryPolygonNotificationService implements PolygonNotificationService {
    private final ConcurrentMap<String, NotificationEvent> events = new ConcurrentHashMap<>();
    private final AtomicLong block = new AtomicLong(1);

    @Override
    public java.util.concurrent.CompletionStage<String> publishNotification(NotificationRequest request) {
        long b = block.incrementAndGet();
        String tx = "0x" + java.util.UUID.randomUUID().toString().replace("-", "");
        NotificationEvent ev = new NotificationEvent(
                "dev", request.contractAddress(), tx, 0, b, "0xblock" + b,
                request.messageId(), request.contentHash(), request.inboxTag(), request.expiresAt(), System.currentTimeMillis());
        events.put(tx, ev);
        return CompletableFuture.completedFuture(tx);
    }

    @Override
    public java.util.concurrent.CompletionStage<List<NotificationEvent>> scan(BlockchainCursor from, BlockchainCursor to) {
        List<NotificationEvent> out = new ArrayList<>();
        for (NotificationEvent e : events.values()) {
            if (e.blockNumber() >= from.lastBlock() && e.blockNumber() <= to.lastBlock()) out.add(e);
        }
        return CompletableFuture.completedFuture(out);
    }

    @Override
    public java.util.concurrent.CompletionStage<TransactionStatus> track(String transactionHash) {
        return CompletableFuture.completedFuture(events.containsKey(transactionHash) ? TransactionStatus.CONFIRMED : TransactionStatus.FAILED);
    }

    @Override
    public java.util.concurrent.CompletionStage<Long> headBlock() {
        return CompletableFuture.completedFuture(block.get());
    }

    /** Test helper simulating a chain reorganization that orphans every block above {@code newHead}. */
    public void dropAbove(long newHead) {
        events.values().removeIf(e -> e.blockNumber() > newHead);
        block.set(newHead);
    }
}
