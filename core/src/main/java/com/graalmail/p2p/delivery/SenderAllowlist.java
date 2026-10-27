package com.graalmail.p2p.delivery;

import com.graalmail.p2p.identity.PolyMailAddress;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Off-chain sender spam filter (spec section 5.5: open publishing with
 * off-chain filtering). An empty allowlist permits every sender; a non-empty
 * one only permits listed PolyMail addresses (normalized).
 */
public class SenderAllowlist {
    private final Set<String> allowed = new HashSet<>();

    public SenderAllowlist() {}

    public SenderAllowlist(List<String> addresses) {
        for (String address : addresses) allow(address);
    }

    public void allow(String address) {
        allowed.add(PolyMailAddress.parse(address).canonical());
    }

    public void remove(String address) {
        allowed.remove(PolyMailAddress.parse(address).canonical());
    }

    public boolean isAllowed(String address) {
        if (allowed.isEmpty()) return true;
        try {
            return allowed.contains(PolyMailAddress.parse(address).canonical());
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isEmpty() { return allowed.isEmpty(); }
}
