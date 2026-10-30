package com.zello.zello.auth;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SessionEncryptionTests {
    @Test
    void sessionAttributesRoundTripWithoutStoringTokensAsReadableText() {
        var configuration = new SessionEncryptionConfiguration();
        configuration.setBeanClassLoader(getClass().getClassLoader());
        var conversions = configuration.sessionConversionService("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        String token = "github-access-token-test-only";
        byte[] first = conversions.convert(token, byte[].class);
        byte[] second = conversions.convert(token, byte[].class);
        assertThat(new String(first, StandardCharsets.ISO_8859_1)).doesNotContain(token);
        assertThat(first).isNotEqualTo(second);
        assertThat(conversions.convert(first, Object.class)).isEqualTo(token);
        first[first.length - 1] ^= 1;
        assertThatThrownBy(() -> conversions.convert(first, Object.class)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> configuration.sessionConversionService("invalid")).isInstanceOf(IllegalStateException.class);
    }
}
