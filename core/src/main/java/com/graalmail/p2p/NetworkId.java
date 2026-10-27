package com.graalmail.p2p;

public enum NetworkId {
    DEVELOPMENT("dev"),
    INTEGRATION("int"),
    TESTNET("test"),
    PRODUCTION("prod");

    private final String code;

    NetworkId(String code) { this.code = code; }
    public String code() { return code; }

    public static NetworkId fromCode(String code) {
        for (NetworkId n : values()) {
            if (n.code.equalsIgnoreCase(code)) return n;
        }
        throw new PolyMailException(com.graalmail.core.error.ErrorCode.CONFIGURATION_ERROR,
                "Unknown network id: " + code, false, true, false);
    }
}