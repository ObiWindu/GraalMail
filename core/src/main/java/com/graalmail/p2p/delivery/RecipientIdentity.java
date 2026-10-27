package com.graalmail.p2p.delivery;

import java.util.List;

/** Local identity needed to recognise and decrypt incoming notifications. */
public record RecipientIdentity(
        String address,
        byte[] encryptionPublicKey,
        byte[] encryptionPrivateKey
) {}
