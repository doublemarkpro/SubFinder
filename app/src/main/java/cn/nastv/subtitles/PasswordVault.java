package cn.nastv.subtitles;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class PasswordVault {
    private static final String ALIAS = "nas-tv-login";

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    static void store(SharedPreferences.Editor edit, String password) throws Exception {
        if (password.isEmpty()) {
            edit.remove("iv").remove("secret");
            return;
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encoded = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        edit.putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        edit.putString("secret", Base64.encodeToString(encoded, Base64.NO_WRAP));
    }

    static String read(SharedPreferences prefs) {
        String iv = prefs.getString("iv", "");
        if (iv.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT)));
            return new String(cipher.doFinal(Base64.decode(prefs.getString("secret", ""), Base64.DEFAULT)),
                    StandardCharsets.UTF_8);
        } catch (Exception ex) {
            prefs.edit().remove("secret").remove("iv").apply();
            return "";
        }
    }
}
