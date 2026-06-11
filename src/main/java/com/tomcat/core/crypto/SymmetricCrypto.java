package com.tomcat.core.crypto;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class SymmetricCrypto {

    private static final String Algorithm = "AES";
    private static final String Transformation = "AES/GCM/NoPadding";
    private static final int GcmIvLength = 12;
    private static final int GcmTagLength = 128;
    private static final int KeySize = 256;

    private SecretKey Key;
    private byte[] RawKey;

    public SymmetricCrypto() {}

    public byte[] GenerateKey() throws Exception {
        KeyGenerator Kg = KeyGenerator.getInstance(Algorithm);
        Kg.init(KeySize, new SecureRandom());
        Key = Kg.generateKey();
        RawKey = Key.getEncoded();
        return RawKey;
    }

    public void SetKey(byte[] KeyBytes) {
        RawKey = KeyBytes;
        Key = new SecretKeySpec(KeyBytes, Algorithm);
    }

    public byte[] GetKey() {
        return RawKey;
    }

    public String GetKeyAsBase64Url() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(RawKey);
    }

    public byte[] Encrypt(byte[] Data) throws Exception {
        if (Key == null) throw new IllegalStateException("No encryption key set");
        byte[] Iv = new byte[GcmIvLength];
        new SecureRandom().nextBytes(Iv);
        Cipher C = Cipher.getInstance(Transformation);
        C.init(Cipher.ENCRYPT_MODE, Key, new GCMParameterSpec(GcmTagLength, Iv));
        byte[] Encrypted = C.doFinal(Data);
        byte[] Result = new byte[GcmIvLength + Encrypted.length];
        System.arraycopy(Iv, 0, Result, 0, GcmIvLength);
        System.arraycopy(Encrypted, 0, Result, GcmIvLength, Encrypted.length);
        return Result;
    }

    public byte[] Decrypt(byte[] Data) throws Exception {
        if (Key == null) throw new IllegalStateException("No encryption key set");
        byte[] Iv = new byte[GcmIvLength];
        System.arraycopy(Data, 0, Iv, 0, GcmIvLength);
        byte[] Ciphertext = new byte[Data.length - GcmIvLength];
        System.arraycopy(Data, GcmIvLength, Ciphertext, 0, Ciphertext.length);
        Cipher C = Cipher.getInstance(Transformation);
        C.init(Cipher.DECRYPT_MODE, Key, new GCMParameterSpec(GcmTagLength, Iv));
        return C.doFinal(Ciphertext);
    }

    public byte[] EncryptString(String Text) throws Exception {
        return Encrypt(Text.getBytes("UTF-8"));
    }

    public String DecryptString(byte[] Data) throws Exception {
        return new String(Decrypt(Data), "UTF-8");
    }

    public String EncryptToBase64(String Text) throws Exception {
        return Base64.getEncoder().encodeToString(EncryptString(Text));
    }

    public String DecryptFromBase64(String B64) throws Exception {
        return DecryptString(Base64.getDecoder().decode(B64));
    }
}
