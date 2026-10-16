package dev.codex.longrange.client;

import java.net.URI;
import java.util.Locale;

/** Local settings are displayed before external requests; every change invalidates consent. */
record PrivacyPolicy(String url, String operator, String contact, int retentionDays, String hash) {
    static final String REVISION = "lrp-privacy-2026-10-v3";
    static PrivacyPolicy current() { return of(LongRangePlayersClient.config()); }
    static PrivacyPolicy of(ClientConfig config) {
        return new PrivacyPolicy(ClientConfig.DEFAULT_RELAY_URL, "honwasa", "iptuuti@gmail.com", 7,
                RoomIdentity.hash("onboarding-v4\n" + REVISION + "\n" + ClientConfig.DEFAULT_RELAY_URL + "\nhonwasa\n" + "iptuuti@gmail.com"));
    }
    boolean ready() { return !url.isBlank() && !operator.isBlank() && !contact.isBlank(); }
    static String normalizeUrl(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI u = URI.create(value.trim());
            if (!"wss".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null
                    || u.getQuery() != null || u.getFragment() != null || !"/ws".equals(u.getRawPath())
                    || u.getPort() == 0 || u.getPort() > 65535) throw new IllegalArgumentException();
            return new URI("wss", null, u.getHost().toLowerCase(Locale.ROOT), u.getPort() == 443 ? -1 : u.getPort(),
                    "/ws", null, null).toASCIIString();
        } catch (Exception error) { throw new IllegalArgumentException("Relay URL must be wss://host/ws without credentials"); }
    }
}
