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
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

public final class CertificateManager {

    private static final String SignAlgorithm = "SHA256WithRSAEncryption";
    private static final int CaKeySize = 4096;
    private static final int LeafKeySize = 2048;

    private final ServerConfig Config;

    private PrivateKey CaPrivateKey;
    private X509Certificate CaCertificate;
    private X500Name CaX500Name;

    public CertificateManager(ServerConfig Config) {
        this.Config = Config;
        EnsureDirs();
    }

    private void EnsureDirs() {
        try {
            Files.createDirectories(Paths.get(Config.GetAgentCertDir()));
            Files.createDirectories(Paths.get(Config.GetKeystorePath()).getParent());
        } catch (IOException E) {
            Logger.Warn("Could not create cert directories: " + E.getMessage());
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
            Config.GetKeystorePath(),
            Config.GetKeystoreType(),
            Config.GetKeystorePassword(),
            Config.GetTruststorePath(),
            Config.GetTruststoreType(),
            Config.GetTruststorePassword(),
            Config.GetTlsProtocol(),
            NeedClientAuth
        );
        Logger.Verbose("SSLContext built — clientAuth=" + NeedClientAuth);
        return Ctx;
    }

    private void LoadOrCreateCa() throws Exception {
        String CaPath = Config.GetCaPath();
        String CaType = Config.GetCaType();
        String CaPass = Config.GetCaPassword();

        if (Files.exists(Paths.get(CaPath))) {
            Logger.Info("Loading existing CA: " + CaPath);
            try {
                KeyStore CaKs = KeystoreLoader.Load(CaPath, CaType, CaPass);
                String Alias = CaKs.aliases().nextElement();
                CaPrivateKey = (PrivateKey) CaKs.getKey(Alias, CaPass.toCharArray());
                CaCertificate = (X509Certificate) CaKs.getCertificate(Alias);
                CaX500Name = X500Name.getInstance(
                    org.bouncycastle.asn1.ASN1Sequence.fromByteArray(
                        CaCertificate.getSubjectX500Principal().getEncoded()
                    )
                );
                Logger.Verbose("CA loaded — CN=" + CaCertificate.getSubjectX500Principal());
            } catch (Exception E) {
                throw new Exception("Failed to load CA from [" + CaPath + "]: " + E.getMessage(), E);
            }
            return;
        }

        Logger.Info("Generating new Certificate Authority");
        try {
            KeyPairGenerator Kpg = KeyPairGenerator.getInstance("RSA");
            Kpg.initialize(CaKeySize);
            KeyPair Pair = Kpg.generateKeyPair();
            CaPrivateKey = Pair.getPrivate();

            CaX500Name = BuildDn(
                Config.GetCaDnCn(),
                Config.GetCaDnO(),
                Config.GetCaDnOu(),
                Config.GetCaDnL(),
                Config.GetCaDnSt(),
                Config.GetCaDnC()
            );

            Date NotBefore = new Date();
            Date NotAfter = Date.from(Instant.now().plus(Duration.ofDays(Config.GetCaValidityDays())));
            BigInteger Serial = BigInteger.valueOf(new SecureRandom().nextLong()).abs();

            X509v3CertificateBuilder Builder = new JcaX509v3CertificateBuilder(
                CaX500Name,
                Serial,
                NotBefore,
                NotAfter,
                CaX500Name,
                Pair.getPublic()
            );
            Builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            Builder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature)
            );

            ContentSigner Signer = new JcaContentSignerBuilder(SignAlgorithm).build(CaPrivateKey);
            CaCertificate = new JcaX509CertificateConverter().getCertificate(Builder.build(Signer));

            SaveKeystore(CaPath, "PKCS12", CaPass, "ca", CaPrivateKey, new X509Certificate[] { CaCertificate });
            AddToTruststore(CaCertificate, "ca");
            Logger.Success("CA created — " + CaX500Name);
        } catch (Exception E) {
            throw new Exception("CA generation failed: " + E.getMessage(), E);
        }
    }

    private void LoadOrCreateServerCert(String ServerHost) throws Exception {
        String KsPath = Config.GetKeystorePath();
        String KsType = Config.GetKeystoreType();
        String KsPass = Config.GetKeystorePassword();

        if (Files.exists(Paths.get(KsPath))) {
            Logger.Info("Loading existing server certificate: " + KsPath);
            try {
                KeystoreLoader.Load(KsPath, KsType, KsPass);
                Logger.Verbose("Server keystore loaded from " + KsPath);
            } catch (Exception E) {
                throw new Exception("Failed to load server keystore [" + KsPath + "]: " + E.getMessage(), E);
            }
            return;
        }

        Logger.Info("Generating server certificate");
        try {
            KeyPairGenerator Kpg = KeyPairGenerator.getInstance("RSA");
            Kpg.initialize(LeafKeySize);
            KeyPair Pair = Kpg.generateKeyPair();

            X500Name Subject = BuildDn(
                Config.GetDnCn(),
                Config.GetDnO(),
                Config.GetDnOu(),
                Config.GetDnL(),
                Config.GetDnSt(),
                Config.GetDnC()
            );

            Date NotBefore = new Date();
            Date NotAfter = Date.from(Instant.now().plus(Duration.ofDays(Config.GetServerValidityDays())));
            BigInteger Serial = BigInteger.valueOf(new SecureRandom().nextLong()).abs();

            X509v3CertificateBuilder Builder = new JcaX509v3CertificateBuilder(
                CaX500Name,
                Serial,
                NotBefore,
                NotAfter,
                Subject,
                Pair.getPublic()
            );
            Builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            Builder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment)
            );
            Builder.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));

            GeneralNamesBuilder SanBuilder = new GeneralNamesBuilder();
            SanBuilder.addName(new GeneralName(GeneralName.dNSName, "localhost"));
            if (!ServerHost.equals("0.0.0.0")) {
                SanBuilder.addName(new GeneralName(GeneralName.iPAddress, ServerHost));
            }
            Builder.addExtension(Extension.subjectAlternativeName, false, SanBuilder.build());

            ContentSigner Signer = new JcaContentSignerBuilder(SignAlgorithm).build(CaPrivateKey);
            X509Certificate Cert = new JcaX509CertificateConverter().getCertificate(Builder.build(Signer));
            Cert.verify(CaCertificate.getPublicKey());

            SaveKeystore(
                KsPath,
                "PKCS12",
                KsPass,
                "server",
                Pair.getPrivate(),
                new X509Certificate[] { Cert, CaCertificate }
            );
            Logger.Success("Server certificate created — " + Subject);
        } catch (Exception E) {
            throw new Exception("Server cert generation failed: " + E.getMessage(), E);
        }
    }

    public String CreateAgentCertificate(String AgentId) throws Exception {
        String AgentName = "Agent-" + AgentId;
        String AgentPath = Config.GetAgentCertDir() + "/" + AgentName + ".p12";

        if (Files.exists(Paths.get(AgentPath))) {
            Logger.Info("Agent cert already exists: " + AgentName);
            return AgentPath;
        }

        Logger.Info("Generating agent certificate: " + AgentName);
        try {
            KeyPairGenerator Kpg = KeyPairGenerator.getInstance("RSA");
            Kpg.initialize(LeafKeySize);
            KeyPair Pair = Kpg.generateKeyPair();

            X500Name Subject = BuildDn(
                AgentName,
                Config.GetDnO(),
                "C2Agents",
                Config.GetDnL(),
                Config.GetDnSt(),
                Config.GetDnC()
            );

            Date NotBefore = new Date();
            Date NotAfter = Date.from(Instant.now().plus(Duration.ofDays(Config.GetAgentValidityDays())));
            BigInteger Serial = BigInteger.valueOf(new SecureRandom().nextLong()).abs();

            X509v3CertificateBuilder Builder = new JcaX509v3CertificateBuilder(
                CaX500Name,
                Serial,
                NotBefore,
                NotAfter,
                Subject,
                Pair.getPublic()
            );
            Builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            Builder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment)
            );
            Builder.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));

            ContentSigner Signer = new JcaContentSignerBuilder(SignAlgorithm).build(CaPrivateKey);
            X509Certificate Cert = new JcaX509CertificateConverter().getCertificate(Builder.build(Signer));
            Cert.verify(CaCertificate.getPublicKey());

            SaveKeystore(
                AgentPath,
                "PKCS12",
                Config.GetKeystorePassword(),
                "agent",
                Pair.getPrivate(),
                new X509Certificate[] { Cert, CaCertificate }
            );
            AddToTruststore(Cert, AgentName);
            Logger.Success("Agent cert created: " + AgentPath);
            return AgentPath;
        } catch (Exception E) {
            throw new Exception("Agent cert generation failed [" + AgentId + "]: " + E.getMessage(), E);
        }
    }

    public void RevokeAgentCertificate(String AgentName) throws Exception {
        String Path = Config.GetAgentCertDir() + "/" + AgentName + ".p12";
        boolean Deleted = Files.deleteIfExists(Paths.get(Path));
        if (Deleted) Logger.Info("Agent cert revoked: " + AgentName);
        else Logger.Warn("Agent cert not found for revocation: " + AgentName);
    }

    private void AddToTruststore(X509Certificate Cert, String Alias) throws Exception {
        String TsPath = Config.GetTruststorePath();
        String TsPass = Config.GetTruststorePassword();
        KeyStore Ts;
        if (Files.exists(Paths.get(TsPath))) {
            Ts = KeystoreLoader.Load(TsPath, Config.GetTruststoreType(), TsPass);
        } else {
            Ts = KeyStore.getInstance("PKCS12");
            Ts.load(null, null);
        }
        Ts.setCertificateEntry(Alias, Cert);
        try (OutputStream Out = new FileOutputStream(TsPath)) {
            Ts.store(Out, TsPass.toCharArray());
        }
        Logger.Verbose("Added to truststore: " + Alias);
    }

    private void SaveKeystore(
        String Path,
        String Type,
        String Password,
        String Alias,
        PrivateKey Key,
        X509Certificate[] Chain
    ) throws Exception {
        Files.createDirectories(Paths.get(Path).getParent());
        KeyStore Ks = KeyStore.getInstance(Type);
        Ks.load(null, null);
        Ks.setKeyEntry(Alias, Key, Password.toCharArray(), Chain);
        try (OutputStream Out = new FileOutputStream(Path)) {
            Ks.store(Out, Password.toCharArray());
        }
        Logger.Verbose("Keystore saved: " + Path);
    }

    private X500Name BuildDn(String Cn, String O, String Ou, String L, String St, String C) {
        return new X500Name("CN=" + Cn + ",O=" + O + ",OU=" + Ou + ",L=" + L + ",ST=" + St + ",C=" + C);
    }
}
