package dev.yiji.qqlocalprobe;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** One local account session; passwords are removed before serialization. */
public final class AccountStore {
    private static final String ALIAS = ClientProfile.KEY_ALIAS;
    private final Context context;
    private final AtomicFile file;

    public AccountStore(Context context) {
        this.context = context.getApplicationContext();
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), ClientProfile.SESSION_FILE));
    }

    public boolean exists() { return file.getBaseFile().isFile(); }

    public String load() throws Exception {
        byte[] bytes = file.readFully();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, bytes, 0, 12));
        return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
    }

    public synchronized void save(String session) throws Exception {
        JSONObject json = new JSONObject(session);
        json.put("password", "");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
        FileOutputStream out = file.startWrite();
        try {
            out.write(cipher.getIV()); out.write(encrypted); file.finishWrite(out);
        } catch (Exception error) { file.failWrite(out); throw error; }
        context.getSharedPreferences("login", 0).edit().putLong("uin", json.getLong("uin")).commit();
    }

    public void clear() { file.delete(); }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }
}
