package io.github.lubara7.tgmax.max;

import java.security.SecureRandom;
import java.util.Map;

/** Wire-specific session metadata. A profile is not evidence of ban immunity. */
public final class MaxSessionProfile {
    public static final String CURRENT_WEB_VERSION = "26.11.6";
    public final String deviceType, appVersion, deviceName, architecture;
    public final int buildNumber;
    private final boolean legacyWeb;

    private MaxSessionProfile(String type, String version, String name, int build, String arch, boolean legacy) {
        if (version == null || !version.matches("[0-9]+(?:\\.[0-9]+){1,3}")) throw new MaxException("MAX_PROFILE_VERSION_INVALID");
        deviceType = type; appVersion = version; deviceName = name; buildNumber = build; architecture = arch;
        legacyWeb = legacy;
    }

    public static MaxSessionProfile web(String version, String deviceName) {
        return new MaxSessionProfile("WEB", version, deviceName, 0, null, false);
    }

    public static MaxSessionProfile legacyWeb(String version) {
        return new MaxSessionProfile("WEB", version, "MAXGRAM CLIENT", 0, null, true);
    }

    public static MaxSessionProfile mobile(String type, String version, String name, int build, String arch) {
        if (!("ANDROID".equals(type) || "IOS".equals(type) || "DESKTOP".equals(type)) || build <= 0)
            throw new MaxException("MAX_MOBILE_PROFILE_INVALID");
        field(name); field(arch);
        return new MaxSessionProfile(type, version, name, build, arch, false);
    }

    public Map<String, Object> webHandshake(String id, String locale, String os, String screen, String timezone, String header) {
        return webHandshake(id, locale, locale, os, screen, timezone, header);
    }

    public Map<String, Object> webHandshake(String id, String locale, String deviceLocale, String os, String screen, String timezone, String header) {
        if (!"WEB".equals(deviceType)) throw new MaxException("MAX_TRANSPORT_PROFILE_MISMATCH");
        field(id); field(header); field(locale); field(deviceLocale); field(os); field(screen); field(timezone);
        if (header.chars().anyMatch(c -> c > 126)) throw new MaxException("MAX_PROFILE_FIELD_INVALID");
        String browser = deviceName;
        if (browser == null || browser.isEmpty()) {
            browser = header.contains("Electron") ? "Electron" : header.contains("Opera") || header.contains("OPR") ? "Opera"
                    : header.contains("YaBrowser") ? "Yandex Browser" : header.contains("Atom") ? "Atom"
                    : header.contains("MSIE") ? "Internet Explorer" : header.contains("Edg") ? "Edge"
                    : header.contains("Firefox") ? "Firefox" : header.contains("Chrome") ? "Chrome"
                    : header.contains("Safari") ? "Safari" : "Web client";
        }
        field(browser);
        Map<String, Object> agent = Maps.of("deviceType", "WEB", "locale", locale,
                "deviceLocale", deviceLocale, "osVersion", os, "deviceName", browser, "headerUserAgent", header,
                "appVersion", appVersion, "screen", screen, "timezone", timezone, "isPwa", false);
        if (!legacyWeb) agent.put("pushDeviceType", "WEBPUSH");
        return Maps.of("deviceId", id, "userAgent", agent);
    }

    public Map<String, Object> mobileHandshake(String id, String instance, long session, String locale,
                                                String os, String screen, String timezone) {
        if ("WEB".equals(deviceType)) throw new MaxException("MAX_TRANSPORT_PROFILE_MISMATCH");
        field(id); field(instance); field(locale); field(os); field(screen); field(timezone);
        if (session <= 0) throw new MaxException("MAX_CLIENT_SESSION_ID_INVALID");
        Map<String, Object> agent = Maps.of("deviceType", deviceType, "appVersion", appVersion,
                "buildNumber", buildNumber, "deviceName", deviceName, "arch", architecture,
                "locale", locale, "deviceLocale", locale, "osVersion", os, "screen", screen, "timezone", timezone);
        if ("ANDROID".equals(deviceType)) agent.put("pushDeviceType", "GCM");
        return Maps.of("deviceId", id, "mt_instanceid", instance, "clientSessionId", session, "userAgent", agent);
    }

    /** Generate new identities only; never rewrite an ID already bound to a token. */
    public static String newMobileDeviceId() {
        byte[] bytes = new byte[8]; new SecureRandom().nextBytes(bytes);
        StringBuilder result = new StringBuilder(16);
        for (byte value : bytes) result.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return result.toString();
    }

    public static String newWebDeviceId() { return java.util.UUID.randomUUID().toString(); }

    private static void field(String value) {
        if (value == null || value.isEmpty() || value.length() > 2048 || value.chars().anyMatch(c -> c < 32 || c == 127))
            throw new MaxException("MAX_PROFILE_FIELD_INVALID");
    }

    @Override public String toString() { return "MaxSessionProfile(" + deviceType + ", " + appVersion + ")"; }
}
