package io.github.lubara7.tgmax.max;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class MaxSessionProfileTest {
    @Test public void webDoesNotLeakMobileFieldsAndPreservesBoundIdentity() {
        String existing = "e65089ff-f214-4904-b2e9-462c342c04f2";
        Map<String, Object> init = MaxSessionProfile.web("26.11.6", null).webHandshake(existing,
                "ru", "Android 14", "800x360 3.0x", "Europe/Moscow", "Mozilla/5.0 Chrome/147.0.0.0");
        assertEquals(existing, init.get("deviceId"));
        assertFalse(init.containsKey("mt_instanceid"));
        assertFalse(init.containsKey("clientSessionId"));
        Map<String, Object> agent = Maps.object(init.get("userAgent"));
        assertEquals("WEB", agent.get("deviceType"));
        assertEquals("Chrome", agent.get("deviceName"));
        assertFalse(agent.containsKey("buildNumber"));
        assertEquals("WEBPUSH", agent.get("pushDeviceType"));
    }

    @Test public void mobileUsesSeparatePayloadAndNumericBuild() {
        Map<String, Object> init = MaxSessionProfile.mobile("ANDROID", "26.23.2", "Google Pixel 7", 6779, "arm64-v8a")
                .mobileHandshake("0123456789abcdef", "fedcba9876543210", 123L,
                        "ru", "Android 14", "420dpi 1080x2400", "Europe/Moscow");
        assertEquals(123L, init.get("clientSessionId"));
        assertEquals("fedcba9876543210", init.get("mt_instanceid"));
        Map<String, Object> agent = Maps.object(init.get("userAgent"));
        assertEquals(6779, agent.get("buildNumber"));
        assertFalse(agent.containsKey("headerUserAgent"));
    }

    @Test public void legacySessionIsNotSilentlyConvertedToTheNewProfile() {
        Map<String, Object> init = MaxSessionProfile.legacyWeb("26.8.8").webHandshake("bound-id", "ru",
                "Android 14", "1080x2400", "UTC", "saved-browser-agent");
        Map<String, Object> agent = Maps.object(init.get("userAgent"));
        assertEquals("26.8.8", agent.get("appVersion"));
        assertEquals("MAXGRAM CLIENT", agent.get("deviceName"));
        assertFalse(agent.containsKey("pushDeviceType"));
    }

    @Test public void newIdentitiesHave16HexCharacters() {
        String first = MaxSessionProfile.newMobileDeviceId(), second = MaxSessionProfile.newMobileDeviceId();
        assertTrue(first.matches("[0-9a-f]{16}"));
        assertTrue(second.matches("[0-9a-f]{16}"));
        assertNotEquals(first, second);
    }

    @Test public void newWebIdentityMatchesOfficialWebGenerator() {
        String value = MaxSessionProfile.newWebDeviceId();
        assertEquals(value, java.util.UUID.fromString(value).toString());
    }

    @Test public void interfaceLanguageDoesNotOverwriteDeviceLocale() {
        Map<String, Object> init = MaxSessionProfile.web("26.11.6", null).webHandshake("bound-id",
                "en", "ru", "Android 14", "800x360 3.0x", "UTC", "Mozilla/5.0 Chrome/147.0.0.0");
        Map<String, Object> agent = Maps.object(init.get("userAgent"));
        assertEquals("en", agent.get("locale"));
        assertEquals("ru", agent.get("deviceLocale"));
    }

    @Test public void webClientCannotAccidentallyUseMobileProfile() {
        assertThrows(MaxException.class, () -> new MaxDevice("0123456789abcdef", "ru", "Android 14",
                "1080x2400", "Europe/Moscow", "Mozilla/5.0", MaxSessionProfile.mobile("ANDROID", "26.23.2", "Pixel", 6779, "arm64-v8a")));
        assertThrows(MaxException.class, () -> MaxSessionProfile.web("26.11.6", null)
                .mobileHandshake("id", "instance", 1L, "ru", "Android", "screen", "UTC"));
    }

    @Test public void missingUserAgentDefersConnectionAndCanRecoverFromInitializationFailure() {
        MaxDevice device = new MaxDevice("0123456789abcdef", "ru", "Android 14", "800x360 3.0x", "UTC", null);
        assertFalse(device.ready().isDone());
        device.userAgentUnavailable();
        assertTrue(device.ready().isCompletedExceptionally());
        device.setUserAgent("Mozilla/5.0 Chrome/147.0.0.0");
        assertTrue(device.ready().isDone());
        assertFalse(device.ready().isCompletedExceptionally());
        assertThrows(MaxException.class, () -> device.setUserAgent("bad\r\nheader"));
        assertThrows(MaxException.class, () -> device.setUserAgent("bad-юникод"));
    }
}
