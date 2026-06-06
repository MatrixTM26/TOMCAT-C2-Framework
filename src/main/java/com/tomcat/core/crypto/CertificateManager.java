package com.tomcat.core.crypto;

import com.tomcat.core.output.Logger;
import com.tomcat.utils.ServerConfig;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.*;
import java.util.*;
import javax.net.ssl.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

public final class CertificateManager {

    private static final String SignAlgorithm = "SHA256WithRSAEncryption";
    private static final int    CaKeySize     = 4096;
    private static final int    LeafKeySize   = 2048;

    private final ServerConfig Config;

    private PrivateKey      CaPrivateKey;
    private X509Certificate CaCertificate;
    private X500Name        CaX500Name;

    public CertificateManager(ServerConfig Config) {
        this.Config = Config;
        EnsureDirs();
    }

    private void EnsureDirs() {
        for (String Dir : new String[]{
                Config.GetAgentCertDir(),
                Paths.get(Config.GetKeystorePath()).getParent().toString(),
                Paths.get(Config.GetCaPath()).getParent().toString(),
                Paths.get(Config.GetTruststorePath()).getParent().toString()
        }) {
            try { Files.createDirectories(Paths.get(Dir)); }
            catch (IOException E) { Logger.Warn("Cannot create dir [" + Dir + "]: " + E.getMessage()); }
        }
    }

    public void Initialize(String ServerHost) throws Exception {
        Logger.Info("Initializing certificate infrastructure");
        LoadOrCreateCa();
        LoadOrCreateServerCert(ServerHost);
        Logger.Success("Certificate infrastructure ready");
    }

    public SSLContext BuildSslContext(boolean NeedClientAuth) throws Exception {
        SSLContext Ctx = KeystoreLoader.BuildSslContext(
            Config.GetKeystorePath(),   Config.GetKeystoreType(),   Config.GetKeystorePassword(),
            Config.GetTruststorePath(), Config.GetTruststoreType(), Config.GetTruststorePassword(),
            Config.GetTlsProtocol(), NeedClientAuth);
        Logger.Verbose("SSLContext built — clientAuth=" + NeedClientAuth);
        return Ctx;
    }

    private void LoadOrCreateCa() throws Exception {
        String CaPath = Config.GetCaPath();
        String CaPass = Config.GetCaPassword();
        String CaType = Config.GetCaType();

        if (Files.exists(Paths.get(CaPath))) {
            Logger.Info("Loading existing CA: " + CaPath);
            try {
                KeyStore CaKs    = KeystoreLoader.Load(CaPath, CaType, CaPass);
                String   Alias   = FirstAlias(CaKs);
                CaPrivateKey     = (PrivateKey)       CaKs.getKey(Alias, CaPass.toCharArray());
                CaCertificate    = (X509Certificate)  CaKs.getCertificate(Alias);
                CaX500Name       = X500Name.getInstance(
                    org.bouncycastle.asn1.ASN1Sequence.fromByteArray(
                        CaCertificate.getSubjectX500Principal().getEncoded()));
                if (CaPrivateKey == null)
                    throw new KeyStoreException("CA keystore has no private key — "
                        + "ensure cert.ca.type matches the file format");
                Logger.Verbose("CA loaded — " + CaCertificate.getSubjectX500Principal());
                return;
            } catch (Exception E) {
                throw new Exception("Failed to load CA [" + CaPath + "]: " + E.getMessage(), E);
            }
        }

        Logger.Info("Generating new Certificate Authority");
        try {
            KeyPairGenerator Kpg = KeyPairGenerator.getInstance("RSA");
            Kpg.initialize(CaKeySize);
            KeyPair Pair = Kpg.generateKeyPair();
            CaPrivateKey  = Pair.getPrivate();
            CaX500Name    = BuildDn(Config.GetCaDnCn(), Config.GetCaDnO(), Config.GetCaDnOu(),
                                    Config.GetCaDnL(),  Config.GetCaDnSt(), Config.GetCaDnC());

            Date NotBefore = new Date();
            Date NotAfter  = DateFromNow(Config.GetCaValidityDays());
            BigInteger Serial = RandomSerial();

            X509v3CertificateBuilder Builder = new JcaX509v3CertificateBuilder(
                CaX500Name, Serial, NotBefore, NotAfter, CaX500Name, Pair.getPublic());
            Builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            Builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature));

            ContentSigner Signer = new JcaContentSignerBuilder(SignAlgorithm).build(CaPrivateKey);
            CaCertificate = new JcaX509CertificateConverter().getCertificate(Builder.build(Signer));

            SaveP12(CaPath, CaPass, "ca", CaPrivateKey, new X509Certificate[]{ CaCertificate });
            ExportPem(CaPath.replace(".p12", ".pem"), CaCertificate, CaPrivateKey);
            AddToTruststore(CaCertificate, "ca");
            Logger.Success("CA created — " + CaX500Name);
        } catch (Exception E) {
            throw new Exception("CA generation failed: " + E.getMessage(), E);
        }
    }

    private void LoadOrCreateServerCert(String ServerHost) throws Exception {
        String KsPath = Config.GetKeystorePath();
        String KsPass = Config.GetKeystorePassword();
        String KsType = Config.GetKeystoreType();

        if (Files.exists(Paths.get(KsPath))) {
            Logger.Info("Loading existing server certificate: " + KsPath);
            try {
                KeyStore Ks    = KeystoreLoader.Load(KsPath, KsType, KsPass);
                String   Alias = FirstAlias(Ks);
                PrivateKey Key = (PrivateKey) Ks.getKey(Alias, KsPass.toCharArray());
                if (Key == null)
                    throw new KeyStoreException("Server keystore has no private key — "
                        + "ensure cert.keystore.type matches the actual file format ("
                        + KsType + " specified, path=" + KsPath + ")");
                Logger.Verbose("Server keystore loaded from " + KsPath);
                return;
            } catch (Exception E) {
                throw new Exception("Failed to load server keystore [" + KsPath + "]: "
                    + E.getMessage(), E);
            }
        }

        Logger.Info("Generating server certificate");
        try {
            KeyPairGenerator Kpg = KeyPairGenerator.getInstance("RSA");
            Kpg.initialize(LeafKeySize);
            KeyPair Pair    = Kpg.generateKeyPair();
            X500Name Subject = BuildDn(Config.GetDnCn(), Config.GetDnO(), Config.GetDnOu(),
                                       Config.GetDnL(),  Config.GetDnSt(), Config.GetDnC());

            Date NotBefore = new Date();
            Date NotAfter  = DateFromNow(Config.GetServerValidityDays());
            BigInteger Serial = RandomSerial();

            X509v3CertificateBuilder Builder = new JcaX509v3CertificateBuilder(
                CaX500Name, Serial, NotBefore, NotAfter, Subject, Pair.getPublic());
            Builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            Builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            Builder.addExtension(Extension.extendedKeyUsage, true,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));

            GeneralNamesBuilder San = new GeneralNamesBuilder();
            San.addName(new GeneralName(GeneralName.dNSName, "localhost"));
            if (!ServerHost.equals("0.0.0.0") && !ServerHost.isEmpty())
                San.addName(new GeneralName(GeneralName.iPAddress, ServerHost));
            Builder.addExtension(Extension.subjectAlternativeName, false, San.build());

            ContentSigner Signer = new JcaContentSignerBuilder(SignAlgorithm).build(CaPrivateKey);
            X509Certificate Cert  = new JcaX509CertificateConverter()
                .getCertificate(Builder.build(Signer));
            Cert.verify(CaCertificate.getPublicKey());

            SaveP12(KsPath, KsPass, "server", Pair.getPrivate(),
                new X509Certificate[]{ Cert, CaCertificate });
            ExportPem(KsPath.replace(".p12", ".pem"), Cert, Pair.getPrivate());
            Logger.Success("Server certificate created — " + Subject);
        } catch (Exception E) {
            throw new Exception("Server cert generation failed: " + E.getMessage(), E);
        }
    }

    public String CreateAgentCertificate(String AgentId) throws Exception {
        String AgentName = "Agent-" + AgentId;
        String AgentPath = Config.GetAgentCertDir() + "/" + AgentName + ".p12";

        if (Files.exists(Paths.get(AgentPath))) {
            Logger.Info("Agent cert already exists: " + AgentPath);
            return AgentPath;
        }

        if (CaCertificate == null || CaPrivateKey == null)
            throw new IllegalStateException("CA not loaded — call Initialize() first");

        Logger.Info("Generating agent certificate: " + AgentName);
        try {
            KeyPairGenerator Kpg = KeyPairGenerator.getInstance("RSA");
            Kpg.initialize(LeafKeySize);
            KeyPair Pair    = Kpg.generateKeyPair();
            X500Name Subject = BuildDn(AgentName, Config.GetDnO(), "C2Agents",
                                       Config.GetDnL(), Config.GetDnSt(), Config.GetDnC());

            Date NotBefore = new Date();
            Date NotAfter  = DateFromNow(Config.GetAgentValidityDays());
            BigInteger Serial = RandomSerial();

            X509v3CertificateBuilder Builder = new JcaX509v3CertificateBuilder(
                CaX500Name, Serial, NotBefore, NotAfter, Subject, Pair.getPublic());
            Builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            Builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            Builder.addExtension(Extension.extendedKeyUsage, true,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));

            ContentSigner Signer = new JcaContentSignerBuilder(SignAlgorithm).build(CaPrivateKey);
            X509Certificate Cert  = new JcaX509CertificateConverter()
                .getCertificate(Builder.build(Signer));
            Cert.verify(CaCertificate.getPublicKey());

            SaveP12(AgentPath, Config.GetKeystorePassword(), "agent", Pair.getPrivate(),
                new X509Certificate[]{ Cert, CaCertificate });
            ExportPem(AgentPath.replace(".p12", ".pem"), Cert, Pair.getPrivate());
            AddToTruststore(Cert, AgentName);
            Logger.Success("Agent cert created: " + AgentPath);
            return AgentPath;
        } catch (Exception E) {
            throw new Exception("Agent cert generation failed [" + AgentId + "]: "
                + E.getMessage(), E);
        }
    }

    public void RevokeAgentCertificate(String AgentName) throws Exception {
        for (String Ext : new String[]{ ".p12", ".pem" }) {
            String Path = Config.GetAgentCertDir() + "/Agent-" + AgentName + Ext;
            boolean Deleted = Files.deleteIfExists(Paths.get(Path));
            if (Deleted) Logger.Info("Agent cert revoked: " + Path);
        }
        RemoveFromTruststore("Agent-" + AgentName);
    }

    private void AddToTruststore(X509Certificate Cert, String Alias) throws Exception {
        String TsPath = Config.GetTruststorePath();
        String TsPass = Config.GetTruststorePassword();
        KeyStore Ts;
        if (Files.exists(Paths.get(TsPath))) {
            try {
                Ts = KeystoreLoader.Load(TsPath, Config.GetTruststoreType(), TsPass);
            } catch (Exception E) {
                Logger.Warn("Truststore load failed, creating new: " + E.getMessage());
                Ts = EmptyPkcs12();
            }
        } else {
            Ts = EmptyPkcs12();
        }
        Ts.setCertificateEntry(Alias, Cert);
        SaveKeystore(Ts, TsPath, TsPass);
        Logger.Verbose("Added to truststore: " + Alias);
    }

    private void RemoveFromTruststore(String Alias) throws Exception {
        String TsPath = Config.GetTruststorePath();
        if (!Files.exists(Paths.get(TsPath))) return;
        try {
            KeyStore Ts = KeystoreLoader.Load(TsPath, Config.GetTruststoreType(),
                Config.GetTruststorePassword());
            if (Ts.containsAlias(Alias)) {
                Ts.deleteEntry(Alias);
                SaveKeystore(Ts, TsPath, Config.GetTruststorePassword());
                Logger.Info("Removed from truststore: " + Alias);
            }
        } catch (Exception E) {
            Logger.Warn("Could not remove from truststore: " + E.getMessage());
        }
    }

    private void SaveP12(String Path, String Password, String Alias,
                          PrivateKey Key, X509Certificate[] Chain) throws Exception {
        Files.createDirectories(Paths.get(Path).getParent());
        KeyStore Ks = EmptyPkcs12();
        Ks.setKeyEntry(Alias, Key, Password.toCharArray(), Chain);
        SaveKeystore(Ks, Path, Password);
        Logger.Verbose("PKCS12 keystore saved: " + Path);
    }

    private void ExportPem(String Path, X509Certificate Cert, PrivateKey Key) {
        try {
            StringBuilder Sb = new StringBuilder();
            Sb.append("-----BEGIN CERTIFICATE-----\n");
            Sb.append(Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(Cert.getEncoded()));
            Sb.append("\n-----END CERTIFICATE-----\n");
            if (Key != null) {
                Sb.append("-----BEGIN PRIVATE KEY-----\n");
                Sb.append(Base64.getMimeEncoder(64, new byte[]{'\n'})
                    .encodeToString(Key.getEncoded()));
                Sb.append("\n-----END PRIVATE KEY-----\n");
            }
            Files.writeString(Paths.get(Path), Sb.toString());
            Logger.Verbose("PEM exported: " + Path);
        } catch (Exception E) {
            Logger.Warn("PEM export failed (non-fatal): " + E.getMessage());
        }
    }

    private static void SaveKeystore(KeyStore Ks, String Path, String Password) throws Exception {
        Files.createDirectories(Paths.get(Path).getParent());
        try (OutputStream Out = new FileOutputStream(Path)) {
            Ks.store(Out, Password == null ? new char[0] : Password.toCharArray());
        }
    }

    private static KeyStore EmptyPkcs12() throws Exception {
        KeyStore Ks = KeyStore.getInstance("PKCS12");
        Ks.load(null, null);
        return Ks;
    }

    private static String FirstAlias(KeyStore Ks) throws KeyStoreException {
        Enumeration<String> Aliases = Ks.aliases();
        if (!Aliases.hasMoreElements())
            throw new KeyStoreException("Keystore is empty — no aliases found");
        return Aliases.nextElement();
    }

    private static Date DateFromNow(int Days) {
        return Date.from(Instant.now().plus(Duration.ofDays(Days)));
    }

    private static BigInteger RandomSerial() {
        return BigInteger.valueOf(new SecureRandom().nextLong()).abs().setBit(63);
    }

    private static X500Name BuildDn(String Cn, String O, String Ou,
                                     String L,  String St, String C) {
        return new X500Name("CN=" + Cn + ",O=" + O + ",OU=" + Ou
            + ",L=" + L + ",ST=" + St + ",C=" + C);
    }
}
