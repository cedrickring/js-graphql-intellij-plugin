package com.intellij.lang.jsgraphql.ide.introspection;

import com.intellij.lang.jsgraphql.ide.project.graphqlconfig.model.GraphQLConfigCertificate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;

public class GraphQLIntrospectionSSLBuilder {
    @NotNull
    public static KeyStore makeKeyStore(final Path certPath, final Path keyPath, final GraphQLConfigCertificate.Encoding format)
        throws UnsupportedEncodingException {
        CertificateFactory certificateFactory;
        try {
            certificateFactory = CertificateFactory.getInstance("X509");
        } catch (CertificateException e) {
            throw new IllegalStateException(e);
        }

        java.security.cert.Certificate[] certChain;
        try (InputStream inputStream = Files.newInputStream(certPath)) {
            Collection<? extends Certificate> certCollection = certificateFactory.generateCertificates(inputStream);
            certChain = certCollection.toArray(new Certificate[0]);
        } catch (CertificateException | IOException e) {
            throw new RuntimeException(e);
        }

        // We only support PEM format for now
        if (format != GraphQLConfigCertificate.Encoding.PEM) {
            throw new UnsupportedEncodingException("Format needs to be specified as PEM");
        }

        KeyStore keyStore;
        try {
            PrivateKey privateKey = generatePEMPrivateKey(keyPath);
            keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            // Not supporting keystore and key passwords yet
            keyStore.load(null, null);
            keyStore.setKeyEntry("1", privateKey, null, certChain);
        } catch (NoSuchAlgorithmException | KeyStoreException | IOException | CertificateException e) {
            throw new IllegalStateException(e);
        }
        return keyStore;
    }

    @Nullable
    private static PrivateKey generatePEMPrivateKey(final Path keyPath) throws IOException {

        String key = Files.readString(keyPath, Charset.defaultCharset());
        String privateKeyPEM = key
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replaceAll(System.lineSeparator(), "")
            .replace("-----END PRIVATE KEY-----", "");

        byte[] decoded = Base64.getDecoder().decode(privateKeyPEM);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
        PrivateKey privateKey;
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            privateKey = kf.generatePrivate(keySpec);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (InvalidKeySpecException e) {
            try {
                KeyFactory kf = KeyFactory.getInstance("EC");
                privateKey = kf.generatePrivate(keySpec);
            } catch (InvalidKeySpecException e1) {
                try {
                    KeyFactory kf = KeyFactory.getInstance("DSA");
                    privateKey = kf.generatePrivate(keySpec);
                } catch (InvalidKeySpecException | NoSuchAlgorithmException e2) {
                    throw new RuntimeException(e2);
                }
            } catch (NoSuchAlgorithmException e1) {
                throw new IllegalStateException(e1);
            }
        }

        return privateKey;
    }
}
