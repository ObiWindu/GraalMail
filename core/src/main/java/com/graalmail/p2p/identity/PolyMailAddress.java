package com.graalmail.p2p.identity;

import com.graalmail.p2p.PolyMailException;
import com.graalmail.core.error.ErrorCode;

import java.util.Locale;

public record PolyMailAddress(String username, String domain) {
    public PolyMailAddress {
        if (username == null || username.isBlank()) throw new PolyMailException(ErrorCode.INVALID_RECIPIENT, "username blank");
        if (domain == null || domain.isBlank()) domain = "graalemail";
    }
    public static PolyMailAddress parse(String raw) {
        if (raw == null || raw.isBlank()) throw new PolyMailException(ErrorCode.INVALID_RECIPIENT, "address blank");
        String s = raw.trim();
        int at = s.indexOf('@');
        String user = at < 0 ? s : s.substring(0, at);
        String dom = at < 0 ? "graalemail" : s.substring(at + 1);
        return new PolyMailAddress(normalize(user), dom.toLowerCase(Locale.ROOT));
    }
    public static String normalize(String username) {
        if (username == null) return "";
        // Versioned normalization: trim, lowercase, collapse internal whitespace.
        return username.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
    public String canonical() { return username + "@" + domain; }
    @Override public String toString() { return canonical(); }
}