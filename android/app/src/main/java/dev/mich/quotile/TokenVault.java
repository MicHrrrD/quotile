package dev.mich.quotile;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Local-only encrypted OAuth storage. Callers must hold LOCK for every operation. */
final class TokenVault {
    static final Object LOCK = new Object();
    // Only in-flight work uses this generation. Process death also destroys all such work.
    static long generation;
    private static final String ALIAS = "quotile.account.v1";
    private static final byte[] AAD = "quotile-account-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_BYTES = 131072;
    private final AtomicFile file;

    TokenVault(Context context) {
        file = new AtomicFile(new File(context.getApplicationContext().getNoBackupFilesDir(), "account-v1.bin"));
    }

    static final class Credentials {
        final String accessToken, refreshToken, accountId, label;
        final long expiresAt;
        Credentials(String access, String refresh, String account, String label, long expires) {
            this.accessToken = access; this.refreshToken = refresh;
            this.accountId = account; this.label = label; this.expiresAt = expires;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("version", 1).put("access", accessToken).put("refresh", refreshToken)
                    .put("account", accountId).put("label", label).put("expires", expiresAt);
        }
    }

    Credentials read() throws Exception {
        if (!file.getBaseFile().exists()) return null;
        byte[] encrypted;
        try (FileInputStream input = file.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_BYTES) throw new java.io.IOException("vault_size");
                output.write(buffer, 0, count);
            }
            encrypted = output.toByteArray();
        }
        if (encrypted.length < 30 || encrypted[0] != 1) throw new java.io.IOException("vault_format");
        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Arrays.copyOfRange(encrypted, 1, 13)));
            cipher.updateAAD(AAD);
            plaintext = cipher.doFinal(encrypted, 13, encrypted.length - 13);
            JSONObject object = new JSONObject(new String(plaintext, StandardCharsets.UTF_8));
            if (object.getInt("version") != 1) throw new java.io.IOException("vault_version");
            String access = object.getString("access"), refresh = object.getString("refresh");
            String account = object.getString("account"), label = object.getString("label");
            if (access.isEmpty() || access.length() > 32768 || refresh.length() > 32768
                    || account.length() > 256 || label.length() > 160) throw new java.io.IOException("vault_values");
            return new Credentials(access, refresh, account, label, object.getLong("expires"));
        } finally {
            Arrays.fill(encrypted, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    void write(Credentials credentials) throws Exception {
        byte[] plaintext = credentials.json().toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] iv = cipher.getIV();
            if (iv.length != 12 || encrypted.length + 13 > MAX_BYTES) throw new java.io.IOException("vault_size");
            output = file.startWrite();
            output.write(1); output.write(iv); output.write(encrypted);
            file.finishWrite(output); output = null;
            Arrays.fill(encrypted, (byte) 0);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            if (output != null) file.failWrite(output);
        }
    }

    void clear() throws Exception {
        file.delete();
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
        if (file.getBaseFile().exists()) throw new java.io.IOException("vault_delete");
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        if (!create) throw new java.io.IOException("vault_key_missing");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }
}
