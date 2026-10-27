package com.graalmail.p2p.mail;

import java.util.Arrays;

/**
 * Encrypted attachment stored in the P2P network (spec section 8.3). The attachment
 * bytes are encrypted with a fresh random content key; only the wrapped key travels
 * inside the encrypted mail body. The public {@link AttachmentRef} reveals no
 * filename or MIME type.
 */
public record AttachmentPackage(
        AttachmentRef ref,
        byte[] wrappedKey,
        byte[] contentHash
) {
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AttachmentPackage p)) return false;
        return java.util.Objects.equals(ref, p.ref)
                && Arrays.equals(wrappedKey, p.wrappedKey)
                && Arrays.equals(contentHash, p.contentHash);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(ref);
    }
}
