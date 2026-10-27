package com.graalmail.p2p.blockchain;

import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class Web3jPolygonNotificationService implements PolygonNotificationService {
    private final List<String> rpcUrls;
    private final String contractAddress;
    private final String chainId;
    private final org.web3j.crypto.Credentials credentials;

    public Web3jPolygonNotificationService(String rpcUrl, String contractAddress, String chainId) {
        this(Collections.singletonList(rpcUrl), contractAddress, chainId, null);
    }

    public Web3jPolygonNotificationService(List<String> rpcUrls, String contractAddress, String chainId, org.web3j.crypto.Credentials credentials) {
        this.rpcUrls = rpcUrls;
        this.contractAddress = contractAddress;
        this.chainId = chainId;
        this.credentials = credentials;
    }

    private Web3j connect() {
        for (String url : rpcUrls) {
            try {
                Web3j w = Web3j.build(new HttpService(url));
                w.ethBlockNumber().send();
                return w;
            } catch (Exception ignored) {}
        }
        throw new RuntimeException("no rpc available");
    }

    @Override
    public CompletionStage<String> publishNotification(NotificationRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (credentials == null) throw new IllegalStateException("credentials required for publishing");
                Web3j web3j = connect();
                org.web3j.abi.datatypes.Function function = new org.web3j.abi.datatypes.Function(
                        "notify",
                        java.util.Arrays.asList(
                                new org.web3j.abi.datatypes.generated.Bytes32(request.inboxTag()),
                                new org.web3j.abi.datatypes.generated.Bytes32(new byte[32]),
                                new org.web3j.abi.datatypes.generated.Bytes32(request.contentHash()),
                                new org.web3j.abi.datatypes.generated.Uint64(request.expiresAt()),
                                new org.web3j.abi.datatypes.generated.Bytes32(new byte[32])),
                        java.util.Collections.emptyList());
                String data = org.web3j.abi.FunctionEncoder.encode(function);
                org.web3j.protocol.core.methods.response.EthGasPrice gasPrice = web3j.ethGasPrice().send();
                BigInteger nonce = web3j.ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.LATEST).send().getTransactionCount();
                org.web3j.crypto.RawTransaction raw = org.web3j.crypto.RawTransaction.createTransaction(
                        nonce, gasPrice.getGasPrice(), BigInteger.valueOf(300_000), contractAddress, BigInteger.ZERO, data);
                byte[] signed = org.web3j.crypto.TransactionEncoder.signMessage(raw, credentials);
                String hex = org.web3j.utils.Numeric.toHexString(signed);
                org.web3j.protocol.core.methods.response.EthSendTransaction resp = web3j.ethSendRawTransaction(hex).send();
                if (resp.hasError()) throw new RuntimeException(resp.getError().getMessage());
                return resp.getTransactionHash();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletionStage<List<NotificationEvent>> scan(BlockchainCursor from, BlockchainCursor to) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Web3j web3j = connect();
                EthFilter filter = new EthFilter(
                        DefaultBlockParameter.valueOf(BigInteger.valueOf(from.lastBlock())),
                        DefaultBlockParameter.valueOf(BigInteger.valueOf(to.lastBlock())),
                        contractAddress);
                List<NotificationEvent> out = new ArrayList<>();
                EthLog log = web3j.ethGetLogs(filter).send();
                for (EthLog.LogResult<?> r : log.getLogs()) {
                    org.web3j.protocol.core.methods.response.Log l = (org.web3j.protocol.core.methods.response.Log) r.get();
                    List<String> topics = l.getTopics();
                    if (topics.size() < 3) continue;
                    byte[] contentHash = new byte[32];
                    byte[] inboxTag = hexToBytes(topics.get(1));
                    String messageId = topics.get(2);
                    long expiresAt = 0;
                    try {
                        String data = l.getData();
                        if (data != null && data.length() >= 66) {
                            contentHash = hexToBytes(data.substring(2, 66));
                        }
                    } catch (Exception ignored) {}
                    out.add(new NotificationEvent(chainId, contractAddress, l.getTransactionHash(), l.getLogIndex().intValue(),
                            l.getBlockNumber().longValue(), l.getBlockHash(), messageId, contentHash, inboxTag, expiresAt, System.currentTimeMillis()));
                }
                return out;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletionStage<TransactionStatus> track(String transactionHash) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Web3j web3j = connect();
                TransactionReceipt receipt = web3j.ethGetTransactionReceipt(transactionHash).send().getResult();
                if (receipt == null) return TransactionStatus.PENDING;
                return receipt.isStatusOK() ? TransactionStatus.CONFIRMED : TransactionStatus.FAILED;
            } catch (Exception e) {
                return TransactionStatus.FAILED;
            }
        });
    }

    private static byte[] hexToBytes(String hex) {
        String h = hex.startsWith("0x") ? hex.substring(2) : hex;
        if (h.length() % 2 != 0) h = "0" + h;
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
