/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.work;

import android.content.Context;

import com.graalmail.android.ui.SettingsActivity;
import com.graalmail.p2p.NetworkId;
import com.graalmail.p2p.PolyMailConfig;
import com.graalmail.p2p.PolyMailNode;
import com.graalmail.p2p.InMemoryPeerNetworkService;
import com.graalmail.p2p.blockchain.InMemoryCursorStore;
import com.graalmail.p2p.blockchain.InMemoryPolygonNotificationService;
import com.graalmail.p2p.blockchain.Web3jPolygonNotificationService;
import com.graalmail.p2p.crypto.DefaultEncryptionService;
import com.graalmail.p2p.delivery.InMemoryDeliveryStore;
import com.graalmail.p2p.delivery.RecipientIdentity;
import com.graalmail.p2p.identity.InMemoryIdentityService;
import com.graalmail.p2p.mailbox.InMemoryInboxRepository;
import com.graalmail.storage.MailStore;

import java.util.Arrays;
import java.util.List;

/**
 * Builds the shared {@link PolyMailNode} from Android settings (spec section 16).
 *
 * <p>The node is registered with {@link PolyMailWorker} and scheduled through
 * {@link PolyMailScheduler}; network code never moves into activities. When
 * PolyMail is disabled or unconfigured this returns empty so ordinary mail
 * sync is unaffected.
 */
public final class AndroidPolyMail {

    private static volatile PolyMailNode instance;

    private AndroidPolyMail() {
    }

    public static synchronized PolyMailNode node() {
        return instance;
    }

    public static synchronized void refresh(Context context, MailStore store) {
        close();
        String enabledRaw = store.setting(SettingsActivity.SETTING_POLYMAIL_ENABLED, "false");
        if (!Boolean.parseBoolean(enabledRaw)) {
            PolyMailScheduler.cancel(context);
            return;
        }
        String rpcRaw = store.setting(SettingsActivity.SETTING_POLYMAIL_RPC, "");
        List<String> rpcUrls = rpcRaw.isBlank() ? List.of()
                : Arrays.stream(rpcRaw.split(",")).map(String::trim)
                        .filter(s -> !s.isEmpty()).toList();
        String contract = store.setting(SettingsActivity.SETTING_POLYMAIL_CONTRACT, "");
        if (rpcUrls.isEmpty() || contract.isBlank()) {
            return;
        }
        String chainId = store.setting(SettingsActivity.SETTING_POLYMAIL_CHAIN, "80002");
        if (chainId.isBlank()) {
            chainId = "80002";
        }
        try {
            PolyMailConfig config = new PolyMailConfig(rpcUrls, contract, chainId,
                    NetworkId.TESTNET, 0, 2, 2);
            InMemoryPeerNetworkService peerNetwork = new InMemoryPeerNetworkService();
            List<com.graalmail.p2p.PeerAddress> mailboxPeers;
            try {
                mailboxPeers = com.graalmail.p2p.PeerAddress.parseList(
                        store.setting(SettingsActivity.SETTING_POLYMAIL_PEERS, ""));
            } catch (RuntimeException e) {
                mailboxPeers = List.of();
            }
            PolyMailNode node = PolyMailNode.builder()
                    .identities(new InMemoryIdentityService())
                    .crypto(new DefaultEncryptionService())
                    .peerNetwork(peerNetwork)
                    .polygon(new Web3jPolygonNotificationService(
                            rpcUrls.get(0), contract, chainId))
                    .inbox(new InMemoryInboxRepository())
                    .mailboxPeers(mailboxPeers)
                    .config(config)
                    .deliveryStore(new InMemoryDeliveryStore())
                    .cursors(new InMemoryCursorStore())
                    .build();
            String identity = store.setting(SettingsActivity.SETTING_POLYMAIL_IDENTITY, "");
            if (!identity.isBlank()) {
                node.localIdentity(
                        new RecipientIdentity(identity.trim(), new byte[0], new byte[0]),
                        List.of(identity.trim()));
            }
            node.start();
            instance = node;
            PolyMailWorker.registerNode(AndroidPolyMail::node);
            PolyMailScheduler.schedule(context, 30);
        } catch (RuntimeException e) {
            instance = null;
        }
    }

    public static synchronized void close() {
        PolyMailNode current = instance;
        instance = null;
        if (current != null) {
            try {
                current.stop();
            } catch (RuntimeException ignored) {
            }
        }
    }

    public static boolean isPolyMailAddress(String email) {
        if (email == null) {
            return false;
        }
        String trimmed = email.trim().toLowerCase(java.util.Locale.ROOT);
        return trimmed.endsWith("@graalemail") || trimmed.endsWith("@polymail");
    }
}
