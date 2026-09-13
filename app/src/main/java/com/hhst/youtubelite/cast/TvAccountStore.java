package com.hhst.youtubelite.cast;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Account tokens stay on this device and are encrypted by Android Keystore. */
final class TvAccountStore {
    private final Context context;
    private final String preferences;
    TvAccountStore(Context context) { this(context, "litube_account"); }
    TvAccountStore(Context context, String preferences) { this.context = context.getApplicationContext(); this.preferences = preferences; }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        String alias = "litube-google-account";
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(alias, null);
    }
    void save(JsonObject data) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        if (!context.getSharedPreferences(preferences, 0).edit().putString("encrypted", value).commit()) throw new java.io.IOException("Could not save account");
    }
    JsonObject load() throws Exception {
        String value = context.getSharedPreferences(preferences, 0).getString("encrypted", null);
        if (value == null) return null;
        String[] parts = value.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return JsonParser.parseString(new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    void clear() { context.getSharedPreferences(preferences, 0).edit().clear().commit(); }
}

