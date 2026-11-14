/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWandu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the recipients of a reply, following KMail semantics: a plain
 * reply goes to the sender only, a reply to all reaches the sender and the
 * other recipients. The own address is never included, and a reply to your
 * own message goes to the original recipients instead.
 */
final class ReplyAddressing {

    private ReplyAddressing() { }

    record ReplyTargets(List<Address> to, List<Address> cc) {
        ReplyTargets {
            to = List.copyOf(to);
            cc = List.copyOf(cc);
        }
    }

    static ReplyTargets targets(Account account, Message message, boolean all) {
        String self = account == null ? null : account.email();
        boolean fromSelf = message.from().hasEmail() && self != null
                && self.equalsIgnoreCase(message.from().email());
        List<Address> to = new ArrayList<>();
        if (message.from().hasEmail() && !fromSelf) {
            to.add(message.from());
        }
        if (all || fromSelf) {
            to.addAll(withoutSelf(self, message.to()));
        }
        List<Address> cc = new ArrayList<>();
        if (all) {
            cc.addAll(withoutSelf(self, message.cc()));
        }
        return new ReplyTargets(to, cc);
    }

    private static List<Address> withoutSelf(String self, List<Address> addresses) {
        List<Address> result = new ArrayList<>();
        for (Address address : addresses) {
            if (address == null || !address.hasEmail()) {
                continue;
            }
            if (self != null && self.equalsIgnoreCase(address.email())) {
                continue;
            }
            result.add(address);
        }
        return result;
    }
}
