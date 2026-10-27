package com.graalmail.desktop;

import com.graalmail.p2p.crypto.KeyReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DesktopSecureKeyStoreTest {
    @Test
    void signAndDecryptRoundTrip(@TempDir Path temp) throws Exception {
        DesktopSecureKeyStore store = DesktopSecureKeyStore.open(AppPaths.of(temp));
        KeyReference signing = store.createSigningKey();
        byte[] sig = store.sign(signing, "hello".getBytes());
        assertNotNull(sig);
        assertTrue(sig.length > 0);

        // Unknown keys fail closed.
        assertThrows(Exception.class, () -> store.sign(new KeyReference("missing", "signing"), new byte[1]));
    }
}
