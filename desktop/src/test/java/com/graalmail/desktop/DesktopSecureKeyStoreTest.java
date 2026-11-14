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

    /**
     * The generated master passphrase must round-trip through its file
     * byte-identically on every read: a passphrase that changes between runs
     * silently invalidates every sealed secret. Loops because the old
     * raw-UTF-8 generation failed only when random bytes ended in whitespace.
     */
    @Test
    void generatedMasterPassphraseRoundTripsThroughFile() throws Exception {
        for (int i = 0; i < 25; i++) {
            Path dir = java.nio.file.Files.createTempDirectory("graalmail-passphrase");
            try {
                AppPaths paths = AppPaths.of(dir);
                char[] first = DesktopSecretStore.resolvePassphrase(paths);
                String onDisk = new String(java.nio.file.Files.readAllBytes(paths.masterPassphraseFile()),
                        java.nio.charset.StandardCharsets.UTF_8);
                char[] second = DesktopSecretStore.resolvePassphrase(paths);
                assertArrayEquals(first, second, "passphrase changed between reads (iteration " + i + ")");
                assertEquals(new String(first), onDisk, "file content differs from the passphrase in use");
            } finally {
                java.nio.file.Files.walk(dir).sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            }
        }
    }
}
