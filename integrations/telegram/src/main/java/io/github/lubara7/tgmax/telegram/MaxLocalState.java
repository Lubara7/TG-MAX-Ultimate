package io.github.lubara7.tgmax.telegram;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import io.github.lubara7.tgmax.max.MaxException;
import io.github.lubara7.tgmax.max.StableIds;
import io.github.lubara7.tgmax.max.MaxSessionProfile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class MaxLocalState implements StableIds.Storage {
    private final AtomicFile idsFile;
    private final AtomicFile sessionFile;
    private final String keyAlias;
    private final Context context;
    private final int account;

    MaxLocalState(Context context, int account) {
        this.context = context;
        this.account = account;
        File directory = context.getNoBackupFilesDir();
        idsFile = new AtomicFile(new File(directory, "max-ids-" + account + ".json"));
        sessionFile = new AtomicFile(new File(directory, "max-session-" + account + ".bin"));
        keyAlias = context.getPackageName() + ".max-session." + account;
    }

    String deviceId() {
        android.content.SharedPreferences p = context.getSharedPreferences("max_device_" + account, Context.MODE_PRIVATE);
        String id = p.getString("id", null);
        if (id == null) {
            id = MaxSessionProfile.newWebDeviceId();
            if (!p.edit().putString("id", id).commit()) throw new MaxException("MAX_STORAGE");
        }
        return id;
    }

    String webUserAgent() {
        return context.getSharedPreferences("max_device_" + account, Context.MODE_PRIVATE).getString("web_user_agent", null);
    }

    void webUserAgent(String value) {
        if (!context.getSharedPreferences("max_device_" + account, Context.MODE_PRIVATE).edit()
                .putString("web_user_agent", value).commit()) throw new MaxException("MAX_STORAGE");
    }

    String profileVersion(String recommended) {
        android.content.SharedPreferences preferences = context.getSharedPreferences("max_device_" + account, Context.MODE_PRIVATE);
        String version = preferences.getString("app_version", null);
        if (version == null) {
            File base = sessionFile.getBaseFile();
            boolean legacy = base.exists() || new File(base.getPath() + ".bak").exists();
            version = legacy ? "26.8.8" : recommended;
            if (!preferences.edit().putString("app_version", version).putBoolean("legacy_profile", legacy).commit())
                throw new MaxException("MAX_STORAGE");
        }
        return version;
    }

    boolean legacyProfile() {
        return context.getSharedPreferences("max_device_" + account, Context.MODE_PRIVATE).getBoolean("legacy_profile", false);
    }

    @Override public synchronized Map<String, String> load() {
        Map<String, String> result = new HashMap<>();
        try (java.io.InputStream in = idsFile.openRead()) {
            JSONObject json = new JSONObject(new String(readBounded(in, 16 * 1024 * 1024), StandardCharsets.UTF_8));
            for (Iterator<String> i = json.keys(); i.hasNext();) { String k = i.next(); result.put(k, json.getString(k)); }
        } catch (FileNotFoundException missing) { return result; }
        catch (Exception e) { throw new MaxException("MAX_ID_STORE", e); }
        return result;
    }

    @Override public synchronized void save(Map<String, String> values) {
        write(idsFile, new JSONObject(values).toString().getBytes(StandardCharsets.UTF_8));
    }

    synchronized String[] session() {
        try (java.io.InputStream raw = sessionFile.openRead()) {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(readBounded(raw, 131_072)));
            int version = in.readUnsignedByte();
            int size = in.readUnsignedByte();
            if (version != 1 || size != 12) throw new MaxException("MAX_STORAGE");
            byte[] iv = new byte[size]; in.readFully(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            DataInputStream decoded = new DataInputStream(new ByteArrayInputStream(cipher.doFinal(readBounded(in, 131_072))));
            return new String[] {decoded.readUTF(), decoded.readUTF()};
        } catch (FileNotFoundException missing) { return null; }
        catch (Exception e) { throw new MaxException("MAX_STORAGE", e); }
    }

    synchronized void session(long userId, String token) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream clear = new DataOutputStream(raw);
            clear.writeUTF(Long.toString(userId)); clear.writeUTF(token);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(raw.toByteArray());
            ByteArrayOutputStream record = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(record);
            out.writeByte(1); out.writeByte(cipher.getIV().length); out.write(cipher.getIV()); out.write(encrypted);
            write(sessionFile, record.toByteArray());
        } catch (Exception e) { throw new MaxException("MAX_STORAGE", e); }
    }

    synchronized void clear() { sessionFile.delete(); idsFile.delete(); }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        SecretKey key = (SecretKey) store.getKey(keyAlias, null);
        if (key != null) return key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private static void write(AtomicFile file, byte[] bytes) {
        FileOutputStream out = null;
        try { out = file.startWrite(); out.write(bytes); file.finishWrite(out); }
        catch (Exception e) { if (out != null) file.failWrite(out); throw new MaxException("MAX_STORAGE", e); }
    }

    private static byte[] readBounded(java.io.InputStream in, int maximum) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] block = new byte[8192];
        int count;
        while ((count = in.read(block)) != -1) {
            if (out.size() + count > maximum) throw new java.io.IOException("Local state too large");
            out.write(block, 0, count);
        }
        return out.toByteArray();
    }
}
