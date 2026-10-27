package com.graalmail.p2p.discovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import com.graalmail.p2p.PeerId;

/**
 * File-backed peer discovery: a DHT stepping stone (spec 6.5, status
 * "replace the in-memory registry in production wiring").
 *
 * <p>Keeps signed {@link PeerRecord}s in memory for lookups and persists them
 * to a bounded file so bootstrap peers survive restarts. Records are validated
 * before use (network id match, non-empty peer id, expiry) and stale entries
 * are dropped by {@link #expireStale}. This is deliberately not a full
 * Kademlia DHT yet: it provides the persistence + validation + bootstrap
 * surface the DHT implementation will extend, without changing callers.
 */
public class PersistentPeerDiscoveryService implements PeerDiscoveryService {

    private static final int MAX_RECORDS = 500;
    private static final int MAX_TRANSPORTS = 8;

    private final Map<String, List<PeerRecord>> byNetwork = new ConcurrentHashMap<>();
    private final Path file;

    public PersistentPeerDiscoveryService(Path file) {
        this.file = file;
        load();
    }

    @Override
    public CompletionStage<Void> announce(PeerRecord record) {
        if (!isValid(record)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid peer record"));
        }
        byNetwork.compute(record.networkId(), (network, list) -> {
            List<PeerRecord> records = list == null ? new ArrayList<>() : new ArrayList<>(list);
            records.removeIf(existing -> existing.peerId().equals(record.peerId()));
            records.add(0, record);
            while (records.size() > MAX_RECORDS) {
                records.remove(records.size() - 1);
            }
            return records;
        });
        persist();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<List<PeerRecord>> findPeers(String networkId) {
        long now = System.currentTimeMillis();
        List<PeerRecord> records = new ArrayList<>(byNetwork.getOrDefault(networkId, List.of()));
        records.removeIf(record -> record.expiresAt() > 0 && now > record.expiresAt());
        return CompletableFuture.completedFuture(List.copyOf(records));
    }

    @Override
    public CompletionStage<Void> expireStale(long now) {
        for (List<PeerRecord> list : byNetwork.values()) {
            synchronized (list) {
                list.removeIf(record -> record.expiresAt() > 0 && now > record.expiresAt());
            }
        }
        persist();
        return CompletableFuture.completedFuture(null);
    }

    private static boolean isValid(PeerRecord record) {
        if (record == null || record.peerId() == null) {
            return false;
        }
        String id = record.peerId().toString();
        if (id == null || id.isBlank()) {
            return false;
        }
        if (record.networkId() == null || record.networkId().isBlank()) {
            return false;
        }
        if (record.transports() != null && record.transports().size() > MAX_TRANSPORTS) {
            return false;
        }
        if (record.signature() != null && record.signature().length > 256) {
            return false;
        }
        return true;
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                PeerRecord record = decode(line.trim());
                if (record != null && isValid(record)) {
                    byNetwork.computeIfAbsent(record.networkId(), k -> new ArrayList<>()).add(record);
                }
                if (byNetwork.values().stream().mapToInt(List::size).sum() > MAX_RECORDS) {
                    break;
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private void persist() {
        if (file == null) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            StringBuilder out = new StringBuilder("# PolyMail peer records v1\n");
            int count = 0;
            for (List<PeerRecord> list : byNetwork.values()) {
                for (PeerRecord record : List.copyOf(list)) {
                    if (count >= MAX_RECORDS) {
                        break;
                    }
                    out.append(encode(record)).append('\n');
                    count++;
                }
            }
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static String encode(PeerRecord record) {
        Base64.Encoder encoder = Base64.getEncoder();
        String transports = record.transports() == null ? ""
                : String.join(",", record.transports());
        String addresses = record.addresses() == null ? ""
                : String.join(",", record.addresses());
        byte[] signature = record.signature() == null ? new byte[0] : record.signature();
        return String.join("\t",
                record.peerId().toString(),
                record.networkId(),
                record.protocolVersion() == null ? "" : record.protocolVersion(),
                encoder.encodeToString(transports.getBytes(StandardCharsets.UTF_8)),
                encoder.encodeToString(addresses.getBytes(StandardCharsets.UTF_8)),
                Boolean.toString(record.mailboxCapable()),
                Long.toString(record.createdAt()),
                Long.toString(record.expiresAt()),
                encoder.encodeToString(signature));
    }

    private static PeerRecord decode(String line) {
        String[] parts = line.split("\t", -1);
        if (parts.length != 9) {
            return null;
        }
        Base64.Decoder decoder = Base64.getDecoder();
        List<String> transports = decodeList(decoder, parts[3]);
        List<String> addresses = decodeList(decoder, parts[4]);
        byte[] signature = parts[8].isEmpty() ? new byte[0] : decoder.decode(parts[8]);
        return new PeerRecord(
                new PeerId(parts[0]), parts[1], parts[2], transports, addresses,
                Boolean.parseBoolean(parts[5]),
                Long.parseLong(parts[6]), Long.parseLong(parts[7]), signature);
    }

    private static List<String> decodeList(Base64.Decoder decoder, String encoded) {
        if (encoded.isEmpty()) {
            return List.of();
        }
        String raw = new String(decoder.decode(encoded), StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(","));
    }
}
