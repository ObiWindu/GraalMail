package com.graalmail.p2p.identity;

import java.util.concurrent.CompletionStage;

public interface IdentityService {
    CompletionStage<IdentityRecord> resolve(String address);
    boolean verify(IdentityRecord record);
    void cache(IdentityRecord record);
    IdentityRecord cached(String address);
}