package com.yay.vpn;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

final class SecureStore {
    private final Context context;
    private final KeyStore keys;
    SecureStore(Context c) throws Exception {
        context=c.getApplicationContext(); keys=KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        if(!keys.containsAlias("yay-device")) {
            KeyPairGenerator g=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder("yay-device",KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build());g.generateKeyPair();
        }
        if(!keys.containsAlias("yay-storage")) {
            KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder("yay-storage",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();
        }
    }
    String publicKey() throws Exception { return enc(keys.getCertificate("yay-device").getPublicKey().getEncoded()); }
    String sign(String value) throws Exception {Signature s=Signature.getInstance("SHA256withECDSA");s.initSign((PrivateKey)keys.getKey("yay-device",null));s.update(value.getBytes(StandardCharsets.UTF_8));return enc(s.sign());}
    synchronized void put(String name,String value) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,keys.getKey("yay-storage",null));
        c.updateAAD(name.getBytes(StandardCharsets.UTF_8));byte[] result=c.doFinal(value.getBytes(StandardCharsets.UTF_8));
        JSONObject o=new JSONObject().put("iv",enc(c.getIV())).put("data",enc(result));
        if(!context.getSharedPreferences("vault",0).edit().putString(name,o.toString()).commit())throw new Exception("Could not save secure session");
    }
    synchronized String get(String name) throws Exception {
        String raw=context.getSharedPreferences("vault",0).getString(name,null);if(raw==null)return "";
        JSONObject o=new JSONObject(raw);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,keys.getKey("yay-storage",null),new GCMParameterSpec(128,dec(o.getString("iv"))));c.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        return new String(c.doFinal(dec(o.getString("data"))),StandardCharsets.UTF_8);
    }
    synchronized void clear(){context.getSharedPreferences("vault",0).edit().clear().commit();}
    void importSeed(String key) {
        try(java.io.InputStream in=context.getAssets().open("seed.enc")) {
            java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();byte[] chunk=new byte[4096];int count;while((count=in.read(chunk))!=-1)buffer.write(chunk,0,count);
            byte[] blob=dec(new String(buffer.toByteArray(),StandardCharsets.UTF_8).trim());
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(dec(key),"AES"),new GCMParameterSpec(128,java.util.Arrays.copyOfRange(blob,0,12)));
            c.updateAAD("yay-vpn-v1".getBytes(StandardCharsets.UTF_8));put("preload",new String(c.doFinal(java.util.Arrays.copyOfRange(blob,12,blob.length)),StandardCharsets.UTF_8));
        } catch(Exception ignored) { /* Optional bundle; live authorized configuration is authoritative. */ }
    }
    static String enc(byte[] b){return Base64.encodeToString(b,Base64.NO_WRAP);}
    static byte[] dec(String s){return Base64.decode(s,Base64.NO_WRAP);}
    static String sha(byte[] b)throws Exception {StringBuilder s=new StringBuilder();for(byte v:MessageDigest.getInstance("SHA-256").digest(b))s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return s.toString();}
}
