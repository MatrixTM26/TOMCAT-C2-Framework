package com.tomcat.core.crypto;

import com.tomcat.core.output.Logger;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.util.*;
import javax.net.ssl.*;

public final class KeystoreLoader {

    private static final String Pkcs12Type = "PKCS12";
    private static final String JksType = "JKS";

    private KeystoreLoader() {}

    public static KeyStore Load(String Path, String TypeHint, String Password) throws Exception {
        AssertFileExists(Path);
        String Resolved = ResolveType(Path, TypeHint);
        Logger.Verbose("Loading keystore: " + Path + " [type=" + Resolved + "]");
        return switch (Resolved) {
            case "PKCS12" -> LoadPkcs12(Path, Password);
            case "JKS" -> LoadJks(Path, Password);
            case "PEM" -> LoadPem(Path, Password);
            case "CRT" -> LoadCrt(Path);
            default -> throw new KeyStoreException("Unsupported keystore type: " + Resolved);
        };
    }

    public static SSLContext BuildSslContext(
        String KeyPath,
        String KeyType,
        String KeyPass,
        String TrustPath,
        String TrustType,
        String TrustPass,
        String TlsProtocol,
        boolean NeedClientAuth
    ) throws Exception {
        KeyStore Ks = Load(KeyPath, KeyType, KeyPass);
        KeyStore Ts = Load(TrustPath, TrustType, TrustPass);

        KeyManagerFactory Kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        Kmf.init(Ks, KeyPass == null ? null : KeyPass.toCharArray());

        TrustManagerFactory Tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        Tmf.init(Ts);

        SSLContext Ctx = SSLContext.getInstance(TlsProtocol);
        Ctx.init(Kmf.getKeyManagers(), Tmf.getTrustManagers(), new SecureRandom());
        Logger.Verbose("SSLContext built — protocol=" + TlsProtocol + " clientAuth=" + NeedClientAuth);
        return Ctx;
    }

    public static String ResolveType(String Path, String TypeHint) {
        if (TypeHint == null || TypeHint.isBlank() || TypeHint.equalsIgnoreCase("AUTO")) return DetectByExtension(Path);
        return TypeHint.toUpperCase();
    }

    private static String DetectByExtension(String Path) {
        String L = Path.toLowerCase();
        if (L.endsWith(".p12") || L.endsWith(".pfx")) return Pkcs12Type;
        if (L.endsWith(".jks")) return JksType;
        if (L.endsWith(".pem")) return "PEM";
        if (L.endsWith(".crt") || L.endsWith(".cer")) return "CRT";
        return Pkcs12Type;
    }

    private static KeyStore LoadPkcs12(String Path, String Password) throws Exception {
        return LoadStandard(Pkcs12Type, Path, Password);
    }

    private static KeyStore LoadJks(String Path, String Password) throws Exception {
        return LoadStandard(JksType, Path, Password);
    }

    private static KeyStore LoadStandard(String Type, String Path, String Password) throws Exception {
        KeyStore Ks = KeyStore.getInstance(Type);
        try (InputStream In = new FileInputStream(Path)) {
            Ks.load(In, Password == null ? null : Password.toCharArray());
        } catch (IOException E) {
            throw new KeyStoreException("Failed to load " + Type + " keystore [" + Path + "]: " + E.getMessage(), E);
        }
        return Ks;
    }

    /**
     * Loads a PEM file. Supports:
     *   - Combined: CERTIFICATE + PRIVATE KEY in one file
     *   - Split:    separate cert and key (key path derived: .pem → -key.pem or same file)
     *   - Cert-only: no private key → treated as trust entry
     *
     * Private key formats supported:
     *   PKCS#8:  -----BEGIN PRIVATE KEY-----
     *   RSA:     -----BEGIN RSA PRIVATE KEY-----
     *   EC:      -----BEGIN EC PRIVATE KEY-----
     */
    public static KeyStore LoadPem(String Path, String Password) throws Exception {
        String Content = Files.readString(Paths.get(Path));
        List<X509Certificate> Certs = ExtractCerts(Content);
        PrivateKey PrivKey = ExtractPrivateKey(Content);

        if (PrivKey == null) {
            String KeyPath = Path.replace(".pem", "-key.pem");
            if (!KeyPath.equals(Path) && Files.exists(Paths.get(KeyPath))) {
                Logger.Verbose("PEM: loading separate key file: " + KeyPath);
                PrivKey = ExtractPrivateKey(Files.readString(Paths.get(KeyPath)));
            }
        }

        if (Certs.isEmpty()) throw new CertificateException("No certificates found in PEM file: " + Path);

        KeyStore Ks = KeyStore.getInstance(Pkcs12Type);
        Ks.load(null, null);

        if (PrivKey != null) {
            Ks.setKeyEntry(
                "pem-key",
                PrivKey,
                Password == null ? null : Password.toCharArray(),
                Certs.toArray(new java.security.cert.Certificate[0])
            );
            Logger.Verbose("PEM: loaded key + " + Certs.size() + " cert(s) from " + Path);
        } else {
            for (int I = 0; I < Certs.size(); I++) Ks.setCertificateEntry("pem-cert-" + I, Certs.get(I));
            Logger.Verbose("PEM: loaded " + Certs.size() + " cert(s) (no private key) from " + Path);
        }
        return Ks;
    }

    public static KeyStore LoadCrt(String Path) throws Exception {
        List<X509Certificate> Certs = ExtractCerts(Files.readString(Paths.get(Path)));
        if (Certs.isEmpty()) throw new CertificateException("No certificates found in CRT file: " + Path);
        KeyStore Ks = KeyStore.getInstance(Pkcs12Type);
        Ks.load(null, null);
        for (int I = 0; I < Certs.size(); I++) Ks.setCertificateEntry("crt-cert-" + I, Certs.get(I));
        Logger.Verbose("CRT: loaded " + Certs.size() + " cert(s) from " + Path);
        return Ks;
    }

    public static List<X509Certificate> ExtractCerts(String Pem) throws Exception {
        CertificateFactory Cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> Result = new ArrayList<>();
        int Pos = 0;
        while (true) {
            int Begin = Pem.indexOf("-----BEGIN CERTIFICATE-----", Pos);
            if (Begin < 0) break;
            int End = Pem.indexOf("-----END CERTIFICATE-----", Begin);
            if (End < 0) break;
            String B64 = Pem.substring(Begin + 27, End).replaceAll("\\s+", "");
            try {
                byte[] Der = Base64.getDecoder().decode(B64);
                Result.add((X509Certificate) Cf.generateCertificate(new ByteArrayInputStream(Der)));
            } catch (Exception E) {
                throw new CertificateException(
                    "Failed to parse certificate at position " + Begin + ": " + E.getMessage(),
                    E
                );
            }
            Pos = End + 25;
        }
        return Result;
    }

    public static PrivateKey ExtractPrivateKey(String Pem) throws Exception {
        String B64 = null;
        String Algo = null;

        if (Pem.contains("-----BEGIN PRIVATE KEY-----")) {
            int S = Pem.indexOf("-----BEGIN PRIVATE KEY-----") + 27;
            int E = Pem.indexOf("-----END PRIVATE KEY-----");
            if (E > S) {
                B64 = Pem.substring(S, E).replaceAll("\\s+", "");
                Algo = "PKCS8";
            }
        } else if (Pem.contains("-----BEGIN RSA PRIVATE KEY-----")) {
            int S = Pem.indexOf("-----BEGIN RSA PRIVATE KEY-----") + 31;
            int E = Pem.indexOf("-----END RSA PRIVATE KEY-----");
            if (E > S) {
                B64 = Pem.substring(S, E).replaceAll("\\s+", "");
                Algo = "RSA";
            }
        } else if (Pem.contains("-----BEGIN EC PRIVATE KEY-----")) {
            int S = Pem.indexOf("-----BEGIN EC PRIVATE KEY-----") + 30;
            int E = Pem.indexOf("-----END EC PRIVATE KEY-----");
            if (E > S) {
                B64 = Pem.substring(S, E).replaceAll("\\s+", "");
                Algo = "EC";
            }
        }

        if (B64 == null || B64.isEmpty()) return null;

        byte[] Der;
        try {
            Der = Base64.getDecoder().decode(B64);
        } catch (IllegalArgumentException E) {
            throw new InvalidKeyException("Private key base64 decode failed: " + E.getMessage());
        }

        if ("PKCS8".equals(Algo)) {
            PKCS8EncodedKeySpec Spec = new PKCS8EncodedKeySpec(Der);
            for (String KAlgo : new String[] { "RSA", "EC", "DSA" }) {
                try {
                    return KeyFactory.getInstance(KAlgo).generatePrivate(Spec);
                } catch (Exception Ignored) {}
            }
            throw new InvalidKeyException("PKCS8 key: no supported algorithm matched (tried RSA/EC/DSA)");
        }

        if ("RSA".equals(Algo)) {
            try {
                return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Der));
            } catch (Exception E) {
                try {
                    return KeyFactory.getInstance("RSA").generatePrivate(ParseRsaDer(Der));
                } catch (Exception E2) {
                    throw new InvalidKeyException("RSA PKCS#1 key parse failed: " + E2.getMessage());
                }
            }
        }

        if ("EC".equals(Algo)) {
            try {
                return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Der));
            } catch (Exception E) {
                throw new InvalidKeyException("EC key parse failed: " + E.getMessage());
            }
        }
        return null;
    }

    /**
     * Convert PKCS#1 RSA DER to JCA key spec parameters.
     * PKCS#1 structure: SEQUENCE { version, n, e, d, p, q, dp, dq, qInv }
     */
    private static java.security.spec.RSAPrivateCrtKeySpec ParseRsaDer(byte[] Der) throws Exception {
        java.io.DataInputStream Din = new java.io.DataInputStream(new ByteArrayInputStream(Der));
        if (Din.read() != 0x30) throw new InvalidKeyException("Not a PKCS#1 SEQUENCE");
        ReadLength(Din);
        if (Din.read() != 0x02) throw new InvalidKeyException("Version tag missing");
        int VLen = ReadLength(Din);
        Din.skipBytes(VLen);
        java.math.BigInteger N = ReadInt(Din);
        java.math.BigInteger E = ReadInt(Din);
        java.math.BigInteger D = ReadInt(Din);
        java.math.BigInteger P = ReadInt(Din);
        java.math.BigInteger Q = ReadInt(Din);
        java.math.BigInteger Dp = ReadInt(Din);
        java.math.BigInteger Dq = ReadInt(Din);
        java.math.BigInteger Qi = ReadInt(Din);
        return new java.security.spec.RSAPrivateCrtKeySpec(N, E, D, P, Q, Dp, Dq, Qi);
    }

    private static int ReadLength(java.io.DataInputStream In) throws IOException {
        int B = In.read();
        if ((B & 0x80) == 0) return B;
        int Octets = B & 0x7F;
        int Len = 0;
        for (int I = 0; I < Octets; I++) Len = (Len << 8) | In.read();
        return Len;
    }

    private static java.math.BigInteger ReadInt(java.io.DataInputStream In) throws IOException {
        if (In.read() != 0x02) throw new IOException("Expected INTEGER tag");
        int Len = ReadLength(In);
        byte[] Bytes = new byte[Len];
        In.readFully(Bytes);
        return new java.math.BigInteger(Bytes);
    }

    private static void AssertFileExists(String Path) throws FileNotFoundException {
        if (!Files.exists(Paths.get(Path))) throw new FileNotFoundException("Certificate file not found: " + Path);
    }
}
