package com.graalmail.p2p.delivery;

import com.graalmail.p2p.DeliveryCoordinator;
import com.graalmail.p2p.NotificationTag;
import com.graalmail.p2p.PolyMailException;
import com.graalmail.p2p.ProtocolConstants;
import com.graalmail.p2p.blockchain.BlockchainCursor;
import com.graalmail.p2p.blockchain.CursorStore;
import com.graalmail.p2p.blockchain.NotificationEvent;
import com.graalmail.p2p.blockchain.PolygonNotificationService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Recipient-side blockchain monitor (spec sections 11.1, 11.2, 11.5, 13.5).
 *
 * <p>Each pass: loads the durable scan cursor, reconciles a confirmation-depth
 * recovery window so chain reorganizations within the window are re-processed
 * idempotently, filters events by chain/contract/tag, and delivers matches through
 * the {@link DeliveryCoordinator}. Works after full termination: missed events are
 * discovered by scanning from the last durable cursor.
 */
public class NotificationScanner {
    private final PolygonNotificationService polygon;
    private final DeliveryCoordinator coordinator;
    private final String chainId;
    private final String contractAddress;
    private final int confirmationDepth;
    private volatile SenderAllowlist allowlist = new SenderAllowlist();

    public NotificationScanner(PolygonNotificationService polygon, DeliveryCoordinator coordinator,
                               String chainId, String contractAddress, int confirmationDepth) {
        this.polygon = polygon;
        this.coordinator = coordinator;
        this.chainId = chainId;
        this.contractAddress = contractAddress;
        this.confirmationDepth = Math.max(0, confirmationDepth);
    }

    /** Off-chain sender spam filter; empty by default (allow all, spec 5.5). */
    public void allowlist(SenderAllowlist allowlist) {
        this.allowlist = allowlist == null ? new SenderAllowlist() : allowlist;
    }

    public CompletionStage<ScanReport> scanOnce(CursorStore cursors, RecipientIdentity recipient,
                                               List<String> senderCandidates) {
        BlockchainCursor cursor = cursors.load(chainId, contractAddress);
        return polygon.headBlock().thenCompose(head -> {
            long base;
            if (head < cursor.lastBlock()) {
                // Deep reorganization orphaned blocks past the head: rewind and rescan.
                base = Math.max(0, head - confirmationDepth);
            } else if (cursor.lastBlock() > 0) {
                base = Math.max(0, cursor.lastBlock() - confirmationDepth);
            } else {
                base = 0;
            }
            BlockchainCursor from = new BlockchainCursor(chainId, contractAddress, base, "");
            BlockchainCursor to = new BlockchainCursor(chainId, contractAddress, head, "");
            return polygon.scan(from, to).thenCompose(events -> {
                List<NotificationEvent> ordered = new ArrayList<>(events);
                ordered.sort(Comparator.comparingLong(NotificationEvent::blockNumber));
                return processSequentially(ordered, 0, recipient, senderCandidates,
                        new int[4], new ArrayList<>(), head);
            }).thenApply(report -> {
                cursors.save(report.newCursor());
                return report;
            });
        });
    }

    private CompletionStage<ScanReport> processSequentially(List<NotificationEvent> events, int index,
                                                           RecipientIdentity recipient, List<String> senderCandidates,
                                                           int[] counts, List<String> failures, long head) {
        if (index >= events.size()) {
            String headHash = "";
            for (int i = events.size() - 1; i >= 0; i--) {
                if (events.get(i).blockNumber() == head && events.get(i).blockHash() != null) {
                    headHash = events.get(i).blockHash();
                    break;
                }
            }
            return CompletableFuture.completedFuture(new ScanReport(counts[0], counts[1], counts[2], counts[3],
                    failures.size(), List.copyOf(failures),
                    new BlockchainCursor(chainId, contractAddress, head, headHash)));
        }
        NotificationEvent event = events.get(index);
        counts[0]++;
        if (!validFor(event, recipient)) {
            counts[3]++;
            return processSequentially(events, index + 1, recipient, senderCandidates, counts, failures, head);
        }
        counts[1]++;
        if (coordinator.hasDelivered(event.messageId())) {
            counts[3]++;
            return processSequentially(events, index + 1, recipient, senderCandidates, counts, failures, head);
        }
        List<String> allowed = senderCandidates.stream().filter(allowlist::isAllowed).toList();
        if (allowed.isEmpty()) {
            counts[3]++;
            return processSequentially(events, index + 1, recipient, senderCandidates, counts, failures, head);
        }
        return trySenders(event, recipient, allowed, 0).thenCompose(ok -> {
            if (ok) counts[2]++;
            else {
                failures.add(event.messageId());
            }
            return processSequentially(events, index + 1, recipient, senderCandidates, counts, failures, head);
        });
    }

    private CompletionStage<Boolean> trySenders(NotificationEvent event, RecipientIdentity recipient,
                                               List<String> senderCandidates, int index) {
        if (index >= senderCandidates.size()) return CompletableFuture.completedFuture(false);
        return coordinator.processNotification(event, senderCandidates.get(index), recipient.encryptionPrivateKey())
                .thenApply(v -> true)
                .toCompletableFuture()
                .exceptionallyCompose(err -> {
                    if (isContentMissing(err)) {
                        // Storage failure, not a wrong-sender verdict: leave the notification
                        // pending for a later pass instead of burning sender candidates.
                        return CompletableFuture.completedFuture(false);
                    }
                    return trySenders(event, recipient, senderCandidates, index + 1);
                });
    }

    private static boolean isContentMissing(Throwable err) {
        Throwable t = err;
        while (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) t = t.getCause();
        return t instanceof PolyMailException p
                && p.code() == com.graalmail.core.error.ErrorCode.CONTENT_NOT_FOUND;
    }

    /** Chain, contract, ABI/field and tag validation (spec section 11.2). */
    private boolean validFor(NotificationEvent event, RecipientIdentity recipient) {
        if (!chainId.equals(event.chainId())) return false;
        if (!contractAddress.equalsIgnoreCase(event.contractAddress())) return false;
        if (event.messageId() == null || event.messageId().isBlank()) return false;
        if (event.contentHash() == null || event.contentHash().length != ProtocolConstants.CONTENT_HASH_BYTES) return false;
        if (event.inboxTag() == null || event.inboxTag().length != ProtocolConstants.CONTENT_HASH_BYTES) return false;
        return NotificationTag.matches(recipient.encryptionPublicKey(), event.messageId(), event.inboxTag());
    }
}
