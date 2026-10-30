package com.zello.zello.auth;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
public class SessionEncryptionConfiguration implements BeanClassLoaderAware {
    private ClassLoader classLoader;

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) { this.classLoader = classLoader; }

    @Bean
    CookieSerializer sessionCookieSerializer(@Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        var cookies = new DefaultCookieSerializer();
        cookies.setCookieName("JSESSIONID");
        cookies.setCookiePath("/");
        cookies.setUseHttpOnlyCookie(true);
        cookies.setSameSite("Lax");
        cookies.setUseSecureCookie(secure);
        return cookies;
    }

    @Bean("springSessionConversionService")
    public GenericConversionService sessionConversionService(@Value("${zello.session.encryption-key:}") String encodedKey) {
        final byte[] key;
        try { key = Base64.getDecoder().decode(encodedKey); }
        catch (IllegalArgumentException exception) { throw new IllegalStateException("SESSION_ENCRYPTION_KEY must be a Base64-encoded 32-byte key"); }
        if (key.length != 32) throw new IllegalStateException("SESSION_ENCRYPTION_KEY must be a Base64-encoded 32-byte key");
        var cipher = new AttributeCipher(key);
        var serializer = new SerializingConverter();
        var deserializer = new DeserializingConverter(classLoader);
        var conversions = new GenericConversionService();
        conversions.addConverter(Object.class, byte[].class, value -> cipher.encrypt(serializer.convert(value)));
        conversions.addConverter(byte[].class, Object.class, value -> deserializer.convert(cipher.decrypt(value)));
        return conversions;
    }

    static final class AttributeCipher {
        private static final byte VERSION = 1;
        private static final byte[] CONTEXT = "zello-session-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        private final SecretKeySpec key;
        private final SecureRandom random = new SecureRandom();

        AttributeCipher(byte[] key) { this.key = new SecretKeySpec(key.clone(), "AES"); }

        byte[] encrypt(byte[] plaintext) {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            try {
                var cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
                cipher.updateAAD(CONTEXT);
                byte[] encrypted = cipher.doFinal(plaintext);
                byte[] envelope = new byte[1 + nonce.length + encrypted.length];
                envelope[0] = VERSION;
                System.arraycopy(nonce, 0, envelope, 1, nonce.length);
                System.arraycopy(encrypted, 0, envelope, 13, encrypted.length);
                return envelope;
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("Unable to protect session data");
            }
        }

        byte[] decrypt(byte[] envelope) {
            if (envelope.length < 29 || envelope[0] != VERSION) throw new IllegalStateException("Invalid encrypted session data");
            try {
                var cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOfRange(envelope, 1, 13)));
                cipher.updateAAD(CONTEXT);
                return cipher.doFinal(envelope, 13, envelope.length - 13);
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("Unable to read protected session data");
            }
        }
    }
}
