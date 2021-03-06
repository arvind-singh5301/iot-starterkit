package commons.utils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.KeySpec;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.bind.DatatypeConverter;

import commons.model.Authentication;
import commons.model.Device;

public class SecurityUtil {

	private static final String CIPHER_ALGORITHM = "PBEWithSHA1AndDESede";

	private static final String TEMP_DIRECTION_NAME = "certificates";

	private static final String SSL_KEYSTORE_SECRET = "hkRPusjglo";

	public static SSLSocketFactory getSSLSocketFactory(Device device, Authentication authentication)
	throws GeneralSecurityException, IOException {
		String secret = authentication.getSecret();
		String pem = authentication.getPem();

		String pemCertificate = pem.substring(
			pem.indexOf("-----BEGIN CERTIFICATE-----\n") + "-----BEGIN CERTIFICATE-----\n".length(),
			pem.indexOf("\n-----END CERTIFICATE-----\n"));

		String pemPrivateKey = pem.substring(
			pem.indexOf("-----BEGIN ENCRYPTED PRIVATE KEY-----\n") +
				"-----BEGIN ENCRYPTED PRIVATE KEY-----\n".length(),
			pem.indexOf("\n-----END ENCRYPTED PRIVATE KEY-----\n"));

		KeyManager[] keyManagers = getKeyManagers(device, pemCertificate, pemPrivateKey, secret);
		TrustManager[] trustManagers = getTrustManagers();

		return getSSLSocketFactory(keyManagers, trustManagers);
	}

	private static SSLSocketFactory getSSLSocketFactory(KeyManager[] keyManagers,
		TrustManager[] trustManagers)
	throws GeneralSecurityException, IOException {

		SSLContext sslContext = SSLContext.getInstance("TLS");
		sslContext.init(keyManagers, trustManagers, new java.security.SecureRandom());

		return sslContext.getSocketFactory();
	}

	private static KeyManager[] getKeyManagers(Device device, String pem,
		String encryptedPrivateKey, String secret)
	throws GeneralSecurityException, IOException {
		PrivateKey privateKey = decryptPrivateKey(encryptedPrivateKey, secret);

		ByteArrayInputStream is = new ByteArrayInputStream(
			DatatypeConverter.parseBase64Binary(pem));

		Certificate certificate;
		try {
			CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
			certificate = certificateFactory.generateCertificate(is);
		}
		finally {
			FileUtil.closeStream(is);
		}

		Path destination = null;
		try {
			destination = Files.createTempDirectory(TEMP_DIRECTION_NAME);
		}
		catch (IllegalArgumentException | SecurityException | IOException e) {
			throw new IOException("Unable to initialize a destination to store PEM", e);
		}

		File p12KeyStore = new File(destination.toFile(),
			device.getPhysicalAddress().replaceAll(":", "") + ".p12");

		KeyStore keyStore;
		try {
			keyStore = KeyStore.getInstance("PKCS12");
			keyStore.load(null, secret.toCharArray());
			try (FileOutputStream p12KeyStoreStream = new FileOutputStream(p12KeyStore)) {
				keyStore.store(p12KeyStoreStream, SSL_KEYSTORE_SECRET.toCharArray());

				keyStore.setKeyEntry("private", privateKey, SSL_KEYSTORE_SECRET.toCharArray(),
					new Certificate[] { certificate });

				keyStore.store(p12KeyStoreStream, SSL_KEYSTORE_SECRET.toCharArray());
			}

		}
		catch (GeneralSecurityException | IOException e) {
			FileUtil.deletePath(destination);

			throw new KeyManagementException("Unable to initialize P12 key store", e);
		}

		try {
			KeyManagerFactory keyManagerFactory = KeyManagerFactory
				.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			keyManagerFactory.init(keyStore, SSL_KEYSTORE_SECRET.toCharArray());

			return keyManagerFactory.getKeyManagers();
		}
		finally {
			FileUtil.deletePath(destination);
		}
	}

	private static PrivateKey decryptPrivateKey(String encryptedPrivateKey, String secret)
	throws GeneralSecurityException, IOException {

		byte[] encodedPrivateKey = DatatypeConverter.parseBase64Binary(encryptedPrivateKey);

		EncryptedPrivateKeyInfo encryptPKInfo = new EncryptedPrivateKeyInfo(encodedPrivateKey);
		Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
		PBEKeySpec pbeKeySpec = new PBEKeySpec(secret.toCharArray());
		SecretKeyFactory secretFactory = SecretKeyFactory.getInstance(CIPHER_ALGORITHM);
		Key pbeKey = secretFactory.generateSecret(pbeKeySpec);
		AlgorithmParameters algorithmParameters = encryptPKInfo.getAlgParameters();
		cipher.init(Cipher.DECRYPT_MODE, pbeKey, algorithmParameters);
		KeySpec pkcsKeySpec = encryptPKInfo.getKeySpec(cipher);
		KeyFactory keyFactory = KeyFactory.getInstance("RSA");

		return keyFactory.generatePrivate(pkcsKeySpec);
	}

	private static TrustManager[] getTrustManagers() {
		return new TrustManager[] { new X509TrustManager() {

			@Override
			public java.security.cert.X509Certificate[] getAcceptedIssuers() {
				return null;
			}

			@Override
			public void checkClientTrusted(java.security.cert.X509Certificate[] arg0, String arg1)
			throws java.security.cert.CertificateException {
				// empty implementation
			}

			@Override
			public void checkServerTrusted(java.security.cert.X509Certificate[] arg0, String arg1)
			throws java.security.cert.CertificateException {
				// empty implementation
			}

		} };
	}

}