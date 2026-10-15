package com.oemtools.poldock;

import android.content.Context;
import android.content.SharedPreferences;
import android.annotation.TargetApi;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** MYBOX 개인 액세스 토큰을 앱 전용 Android Keystore 키로 암호화한다. */
final class SecureTokenStore {
    private static final String KEY_ALIAS = "poldock_mybox_pat_v1";
    private static final String PREF_IV = "parking_mybox_token_iv";
    private static final String PREF_VALUE = "parking_mybox_token_value";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private SecureTokenStore() {
    }

    static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    static synchronized boolean hasToken(Context context) {
        return !get(context).isEmpty();
    }

    static synchronized String get(Context context) {
        if (!isSupported()) return "";
        SharedPreferences preferences = preferences(context);
        String ivText = preferences.getString(PREF_IV, "");
        String valueText = preferences.getString(PREF_VALUE, "");
        if (ivText.isEmpty() || valueText.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)));
            byte[] clear = cipher.doFinal(Base64.decode(valueText, Base64.NO_WRAP));
            return new String(clear, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 키가 초기화됐거나 앱 데이터가 복원된 경우 사용할 수 없는 암호문은 제거한다.
            clear(context);
            return "";
        }
    }

    static synchronized boolean save(Context context, String token) {
        token = token == null ? "" : token.trim();
        if (token.isEmpty()) {
            clear(context);
            return true;
        }
        if (!isSupported()) return false;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            preferences(context).edit()
                    .putString(PREF_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString(PREF_VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .apply();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static synchronized void clear(Context context) {
        preferences(context).edit().remove(PREF_IV).remove(PREF_VALUE).apply();
    }

    static boolean isInternalPreference(String key) {
        return PREF_IV.equals(key) || PREF_VALUE.equals(key);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(ShortcutStore.PREF, Context.MODE_PRIVATE);
    }

    @TargetApi(Build.VERSION_CODES.M)
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
