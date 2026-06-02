package com.tomcat.core.crypto;

import com.tomcat.core.output.Logger;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.*;
import java.util.*;

public class CertificateManager {
    private static final String KeystoreType = "PKCS12";
    private static final String SignAlgorithm = "SHA256WithRSAEncryption";
    private static final int KeySize = 2048;

    private final String CertsDir;
    private final String ServerKeystorePath;
    private final String TruststorePath;
    private final String KeystorePassword;
    private final Map<String, Map<String, String>> AgentMetadata = new HashMap<>();

    private KeyStore ServerKeystore;
    private KeyStore Truststore;
    private PrivateKey CaPrivateKey;
    private X509Certificate CaCertificate;

    public CertificateManager(String CertsDir, String KeystorePassword) {
        this.CertsDir = CertsDir;
        this.KeystorePassword = KeystorePassword;
        this.ServerKeystorePath = CertsDir + "/server.p12";
        this.TruststorePath = CertsDir + "/truststore.p12";
        CreateDirectories();
    }

    private void CreateDirectories() {
        try {
            Files.createDirectories(Paths.get(CertsDir));
            Files.createDirectories(Paths.get(CertsDir + "/agents"));
        } catch (IOException E) {
            Logger.ErrorMsg("Failed to create certificate directories: " + E.getMessage());
        }
    }

    public void Initialize(String ServerHost) throws Exception {
        Logger.Messages("Initializing MTLS Certificate Infrastructure");
        CreateCa();
        CreateServerCertificate(ServerHost);
        Logger.Messages("Certificate Infrastructure Ready");
    }

    public void CreateCa() throws Exception {
        String CaPath = CertsDir + "/ca.p12";
        if (Files.exists(Paths.get(CaPath))) {
            Logger.Messages("Loading Existing CA");
            KeyStore CaKs = KeyStore.getInstance(KeystoreType);
            try (InputStream In = new FileInputStream(CaPath)) {
                CaKs.load(In, KeystorePassword.toCharArray());
            }
            CaPrivateKey = (PrivateKey) CaKs.getKey("ca", KeystorePassword.toCharArray());
            CaCertificate = (X509Certificate) CaKs.getCertificate("ca");
            return;
        }

        Logger.Messages("Generating Certificate Authority");
        KeyPairGenerator Kpg = KeyPairGenerator.getInstance("RSA");
        Kpg.initialize(4096);
        KeyPair CaPair = Kpg.generateKeyPair();
        CaPrivateKey = CaPair.getPrivate();

        X500Name CaName = new X500Name(
            "C=US,ST=Cybertron,L=DarkNet,O=TOMCAT C2 Frameworks V2,OU=ManInTheMatrix,CN=TOMCAT C2 Root CA"
        );
        Date NotBefore = new Date();
        Date NotAfter = Date.from(Instant.now().plus(Duration.ofDays(3650)));
        BigInteger Serial = BigInteger.valueOf(new SecureRandom().nextLong()).abs();

        X509v3CertificateBuilder Builder = new JcaX509v3CertificateBuilder(
            CaName, Serial, NotBefore, NotAfter, CaName, CaPair.getPublic()
        );
        Builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        Builder.addExtension(Extension.keyUsage, true,
            new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature));

        ContentSigner Signer = new JcaContentSignerBuilder(SignAlgorithm).build(CaPrivateKey);
        CaCertificate = new JcaX509CertificateConverter().getCertificate(Builder.build(Signer));

        KeyStore CaKs = KeyStore.getInstance(KeystoreType);
        CaKs.load(null, null);
        CaKs.setKeyEntry("ca", CaPrivateKey, KeystorePassword.toCharArray(),
            new X509Certificate[]{CaCertificate});
        try (OutputStream Out = new FileOutputStream(CaPath)) {
            CaKs.store(Out, KeystorePassword.toCharArray());
        }

        AddToTruststore(CaCertificate, "ca");
        Logger.Messages("CA Created Successfully");
    }

    public void CreateServerCertificate(String ServerHost) throws Exception {
        if (Files.exists(Paths.get(ServerKeystorePath))) {
            Logger.Messages("Loading Existing Server Certificate");
            ServerKeystore = KeyStore.getInstance(KeystoreType);
            try (InputStream In = new FileInputStream(ServerKeystorePath)) {
                ServerKeystore.load(In, KeystorePassword.toCharArray());
            }
            return;
        }

        Logger.Messages("Generating Server Certificate");
        KeyPairGenerator Kpg = KeyPairGenerator.getInstance("RSA");
        Kpg.initialize(KeySize);
        KeyPair Pair = Kpg.generateKeyPair();

        X500Name Subject = new X500Name(
            "C=US,ST=Cybertron,L=DarkNet,O=TOMCAT C2 Frameworks,OU=ManInTheMatrix,CN=TOMCAT C2 Server"
        );
        X500Name IssuerName = new X500Name(CaCertificate.getSubjectX500Principal().getName());
        Date NotBefore = new Date();
        Date NotAfter = Date.from(Instant.now().plus(Duration.ofDays(365)));
        BigInteger Serial = BigInteger.valueOf(new SecureRandom().nextLong()).abs();

        X509v3CertificateBuilder Builder = new JcaX509v3CertificateBuilder(
            IssuerName, Serial, NotBefore, NotAfter, Subject, Pair.getPublic()
        );
        Builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        Builder.addExtension(Extension.keyUsage, true,
            new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        Builder.addExtension(Extension.extendedKeyUsage, true,
            new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));

        GeneralNamesBuilder SanBuilder = new GeneralNamesBuilder();
        SanBuilder.addName(new GeneralName(GeneralName.dNSName, "localhost"));
        if (!ServerHost.equals("0.0.0.0")) {
            SanBuilder.addName(new GeneralName(GeneralName.iPAddress, ServerHost));
        }
        Builder.addExtension(Extension.subjectAlternativeName, false, SanBuilder.build());

        ContentSigner Signer = new JcaContentSignerBuilder(SignAlgorithm).build(CaPrivateKey);
        X509Certificate Cert = new JcaX509CertificateConverter().getCertificate(Builder.build(Signer));

        ServerKeystore = KeyStore.getInstance(KeystoreType);
        ServerKeystore.load(null, null);
        ServerKeystore.setKeyEntry("server", Pair.getPrivate(), KeystorePassword.toCharArray(),
            new X509Certificate[]{Cert, CaCertificate});
        try (OutputStream Out = new FileOutputStream(ServerKeystorePath)) {
            ServerKeystore.store(Out, KeystorePassword.toCharArray());
        }
        Logger.Messages("Server Certificate Created Successfully");
    }

    public String CreateAgentCertificate(String AgentId, boolean UseRawName, int ValidDays) throws Exception {
        String AgentName = UseRawName ? AgentId : "Agent-" + AgentId;
        String AgentPath = CertsDir + "/agents/" + AgentName + ".p12";

        if (Files.exists(Paths.get(AgentPath))) {
            Logger.Messages("Agent Certificate Already Exists: " + AgentName);
            return AgentPath;
        }

        Logger.Messages("Generating Agent Certificate: " + AgentName);
        KeyPairGenerator Kpg = KeyPairGenerator.getInstance("RSA");
        Kpg.initialize(KeySize);
        KeyPair Pair = Kpg.generateKeyPair();

        X500Name Subject = new X500Name(
            "C=US,ST=Cybertron,L=DarkNet,O=TOMCAT C2 Frameworks,OU=C2Agents,CN=" + AgentName
        );
        X500Name IssuerName = new X500Name(CaCertificate.getSubjectX500Principal().getName());
        Date NotBefore = new Date();
        Date NotAfter = Date.from(Instant.now().plus(Duration.ofDays(ValidDays)));
        BigInteger Serial = BigInteger.valueOf(new SecureRandom().nextLong()).abs();

        X509v3CertificateBuilder Builder = new JcaX509v3CertificateBuilder(
            IssuerName, Serial, NotBefore, NotAfter, Subject, Pair.getPublic()
        );
        Builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        Builder.addExtension(Extension.keyUsage, true,
            new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        Builder.addExtension(Extension.extendedKeyUsage, true,
            new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));

        ContentSigner Signer = new JcaContentSignerBuilder(SignAlgorithm).build(CaPrivateKey);
        X509Certificate Cert = new JcaX509CertificateConverter().getCertificate(Builder.build(Signer));

        KeyStore AgentKs = KeyStore.getInstance(KeystoreType);
        AgentKs.load(null, null);
        AgentKs.setKeyEntry("agent", Pair.getPrivate(), KeystorePassword.toCharArray(),
            new X509Certificate[]{Cert, CaCertificate});
        try (OutputStream Out = new FileOutputStream(AgentPath)) {
            AgentKs.store(Out, KeystorePassword.toCharArray());
        }

        Map<String, String> Meta = new HashMap<>();
        Meta.put("Created", new Date().toString());
        Meta.put("ValidDays", String.valueOf(ValidDays));
        Meta.put("Path", AgentPath);
        AgentMetadata.put(AgentName, Meta);

        AddToTruststore(Cert, AgentName);
        Logger.Messages("Agent Certificate Created: " + AgentName);
        return AgentPath;
    }

    private void AddToTruststore(X509Certificate Cert, String Alias) throws Exception {
        KeyStore Ts;
        if (Files.exists(Paths.get(TruststorePath))) {
            Ts = KeyStore.getInstance(KeystoreType);
            try (InputStream In = new FileInputStream(TruststorePath)) {
                Ts.load(In, KeystorePassword.toCharArray());
            }
        } else {
            Ts = KeyStore.getInstance(KeystoreType);
            Ts.load(null, null);
        }
        Ts.setCertificateEntry(Alias, Cert);
        try (OutputStream Out = new FileOutputStream(TruststorePath)) {
            Ts.store(Out, KeystorePassword.toCharArray());
        }
        Truststore = Ts;
    }

    public SSLContext CreateServerSslContext() throws Exception {
        KeyManagerFactory Kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        Kmf.init(ServerKeystore, KeystorePassword.toCharArray());

        TrustManagerFactory Tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        Tmf.init(Truststore);

        SSLContext Ctx = SSLContext.getInstance("TLSv1.3");
        Ctx.init(Kmf.getKeyManagers(), Tmf.getTrustManagers(), new SecureRandom());
        return Ctx;
    }

    public Map<String, Map<String, String>> ListAgents() {
        return Collections.unmodifiableMap(AgentMetadata);
    }

    public void RevokeAgent(String AgentName) throws Exception {
        Files.deleteIfExists(Paths.get(CertsDir + "/agents/" + AgentName + ".p12"));
        AgentMetadata.remove(AgentName);
        Logger.Messages("Agent Certificate Revoked: " + AgentName);
    }
}