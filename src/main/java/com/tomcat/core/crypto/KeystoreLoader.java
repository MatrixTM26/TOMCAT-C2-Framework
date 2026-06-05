package com.tomcat.core.crypto;

import com.tomcat.core.output.Logger;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import javax.net.ssl.*;

/**
 * KeystoreLoader — loads SSL keystores / truststores from multiple formats.
 *
 * Supported types (set via cert.*.type in server.properties):
 *   PKCS12  → .p12 / .pfx
 *   JKS     → .jks
 *   PEM     → .pem (cert + private key in same file or separate)
 *   CRT     → .crt / .cer (certificate only, no private key — truststore use)
 *   AUTO    → detect by file extension
 */
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
        Kmf.init(Ks, KeyPass.toCharArray());

        TrustManagerFactory Tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        Tmf.init(Ts);

        SSLContext Ctx = SSLContext.getInstance(TlsProtocol);
        Ctx.init(Kmf.getKeyManagers(), Tmf.getTrustManagers(), new SecureRandom());

        Logger.Verbose("SSLContext built — protocol=" + TlsProtocol + " clientAuth=" + NeedClientAuth);
        return Ctx;
    }

    public static String ResolveType(String Path, String TypeHint) {
        if (TypeHint == null || TypeHint.isBlank() || TypeHint.equalsIgnoreCase("AUTO")) {
            return DetectByExtension(Path);
        }
        return TypeHint.toUpperCase();
    }

    private static String DetectByExtension(String Path) {
        String Lower = Path.toLowerCase();
        if (Lower.endsWith(".p12") || Lower.endsWith(".pfx")) return Pkcs12Type;
        if (Lower.endsWith(".jks")) return JksType;
        if (Lower.endsWith(".pem")) return "PEM";
        if (Lower.endsWith(".crt") || Lower.endsWith(".cer")) return "CRT";
        return Pkcs12Type;
    }

    private static KeyStore LoadPkcs12(String Path, String Password) throws Exception {
        return LoadStandardKeystore(Pkcs12Type, Path, Password);
    }

    private static KeyStore LoadJks(String Path, String Password) throws Exception {
        return LoadStandardKeystore(JksType, Path, Password);
    }

    private static KeyStore LoadStandardKeystore(String Type, String Path, String Password) throws Exception {
        KeyStore Ks = KeyStore.getInstance(Type);
        try (InputStream In = new FileInputStream(Path)) {
            Ks.load(In, Password == null ? null : Password.toCharArray());
        } catch (IOException E) {
            throw new KeyStoreException("Failed to load " + Type + " keystore [" + Path + "]: " + E.getMessage(), E);
        }
        return Ks;
    }

    /**
     * Loads a PEM file that contains either:
     *   a) CERTIFICATE + PRIVATE KEY sections (combined PEM)
     *   b) Only a CERTIFICATE (treated as truststore entry)
     *
     * The private key must be PKCS#8 (-----BEGIN PRIVATE KEY-----).
     * RSA keys in PKCS#1 (-----BEGIN RSA PRIVATE KEY-----) are also handled.
     */
    private static KeyStore LoadPem(String Path, String Password) throws Exception {
        String Content = Files.readString(Paths.get(Path));
        List<X509Certificate> Certs = ExtractCerts(Content);
        PrivateKey PrivKey = ExtractPrivateKey(Content);

        if (Certs.isEmpty()) {
            throw new CertificateException("No certificates found in PEM file: " + Path);
        }

        KeyStore Ks = KeyStore.getInstance(Pkcs12Type);
        Ks.load(null, null);

        if (PrivKey != null) {
            Ks.setKeyEntry(
                "pem-key",
                PrivKey,
                Password == null ? null : Password.toCharArray(),
                Certs.toArray(new Certificate[0])
            );
            Logger.Verbose("PEM: loaded key + " + Certs.size() + " cert(s)");
        } else {
            for (int I = 0; I < Certs.size(); I++) {
                Ks.setCertificateEntry("pem-cert-" + I, Certs.get(I));
            }
            Logger.Verbose("PEM: loaded " + Certs.size() + " cert(s) (no private key)");
        }
        return Ks;
    }

    private static KeyStore LoadCrt(String Path) throws Exception {
        List<X509Certificate> Certs = ExtractCerts(Files.readString(Paths.get(Path)));
        if (Certs.isEmpty()) {
            throw new CertificateException("No certificates found in CRT file: " + Path);
        }
        KeyStore Ks = KeyStore.getInstance(Pkcs12Type);
        Ks.load(null, null);
        for (int I = 0; I < Certs.size(); I++) {
            Ks.setCertificateEntry("crt-cert-" + I, Certs.get(I));
        }
        Logger.Verbose("CRT: loaded " + Certs.size() + " cert(s)");
        return Ks;
    }

    private static List<X509Certificate> ExtractCerts(String Pem) throws Exception {
        CertificateFactory Cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> Result = new ArrayList<>();
        int Start = 0;
        while (true) {
            int Begin = Pem.indexOf("-----BEGIN CERTIFICATE-----", Start);
            int End = Pem.indexOf("-----END CERTIFICATE-----", Begin);
            if (Begin < 0 || End < 0) break;
            String B64 = Pem.substring(Begin + 27, End).replaceAll("\\s", "");
            byte[] Der = Base64.getDecoder().decode(B64);
            Result.add((X509Certificate) Cf.generateCertificate(new ByteArrayInputStream(Der)));
            Start = End + 25;
        }
        return Result;
    }

    private static PrivateKey ExtractPrivateKey(String Pem) throws Exception {
        String[] Markers = { "-----BEGIN PRIVATE KEY-----", "-----END PRIVATE KEY-----", "PKCS8" };
        int Begin = Pem.indexOf(Markers[0]);
        int End = Pem.indexOf(Markers[1]);

        boolean IsPkcs8 = Begin >= 0 && End > Begin;

        if (!IsPkcs8) {
            Begin = Pem.indexOf("-----BEGIN RSA PRIVATE KEY-----");
            End = Pem.indexOf("-----END RSA PRIVATE KEY-----");
            if (Begin < 0 || End <= Begin) return null;
            Begin += 31;
            End = Pem.indexOf("-----END RSA PRIVATE KEY-----");
        } else {
            Begin += 28;
        }

        String B64 = Pem.substring(Begin, End).replaceAll("\\s", "");
        byte[] Der = Base64.getDecoder().decode(B64);

        try {
            KeyFactory Kf = KeyFactory.getInstance("RSA");
            return Kf.generatePrivate(new PKCS8EncodedKeySpec(Der));
        } catch (Exception E) {
            try {
                KeyFactory Kf = KeyFactory.getInstance("EC");
                return Kf.generatePrivate(new PKCS8EncodedKeySpec(Der));
            } catch (Exception E2) {
                throw new InvalidKeyException("Cannot parse private key (tried RSA + EC): " + E2.getMessage());
            }
        }
    }

    private static void AssertFileExists(String Path) throws FileNotFoundException {
        if (!Files.exists(Paths.get(Path))) {
            throw new FileNotFoundException("Certificate file not found: " + Path);
        }
    }
}
