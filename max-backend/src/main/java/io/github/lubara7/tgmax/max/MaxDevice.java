package io.github.lubara7.tgmax.max;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class MaxDevice {
    public final String id;
    public volatile String language;
    public final String osVersion;
    public final String screen;
    public final String timezone;
    public final String deviceLocale;
    private volatile String userAgent;
    public final MaxSessionProfile profile;
    private CompletableFuture<Void> ready = new CompletableFuture<>();

    public MaxDevice(String id, String language, String osVersion, String screen, String timezone, String userAgent) {
        this(id, language, osVersion, screen, timezone, userAgent,
                MaxSessionProfile.web(MaxSessionProfile.CURRENT_WEB_VERSION, null));
    }

    public MaxDevice(String id, String language, String osVersion, String screen, String timezone,
                     String userAgent, MaxSessionProfile profile) {
        this(id, language, osVersion, screen, timezone, userAgent, profile, language);
    }

    public MaxDevice(String id, String language, String osVersion, String screen, String timezone,
                     String userAgent, MaxSessionProfile profile, String deviceLocale) {
        if (!"WEB".equals(profile.deviceType)) throw new MaxException("MAX_TRANSPORT_PROFILE_MISMATCH");
        this.id = id;
        this.language = language;
        this.osVersion = osVersion;
        this.screen = screen;
        this.timezone = timezone;
        this.deviceLocale = deviceLocale;
        this.profile = profile;
        if (userAgent != null && !userAgent.isEmpty()) setUserAgent(userAgent);
    }

    public synchronized CompletableFuture<Void> ready() { return ready; }

    public synchronized void setUserAgent(String value) {
        profile.webHandshake(id, language, deviceLocale, osVersion, screen, timezone, value);
        userAgent = value;
        if (ready.isCompletedExceptionally()) ready = CompletableFuture.completedFuture(null);
        else ready.complete(null);
    }

    public synchronized void userAgentUnavailable() { ready.completeExceptionally(new MaxException("MAX_USER_AGENT_UNAVAILABLE")); }

    public synchronized Map<String, Object> handshake() {
        return profile.webHandshake(id, language, deviceLocale, osVersion, screen, timezone, userAgent);
    }
    @Override public String toString() { return "MaxDevice([redacted])"; }
}
