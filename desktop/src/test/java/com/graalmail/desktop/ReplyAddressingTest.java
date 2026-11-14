/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWandu <Obi.wandu@proton.me>)
 */
package com.graalmail.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.graalmail.core.domain.Account;
import com.graalmail.core.domain.Address;
import com.graalmail.core.domain.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplyAddressingTest {

    private static Account account(String email) {
        return Account.builder("a1", "gmail").email(email).build();
    }

    private static Message message(Address from, List<Address> to, List<Address> cc) {
        return Message.builder("m1")
                .from(from)
                .to(to)
                .cc(cc)
                .build();
    }

    private static List<String> emails(List<Address> addresses) {
        return addresses.stream().map(Address::email).toList();
    }

    @Test
    void plainReplyGoesToTheSenderOnly() {
        Message message = message(Address.of("colleague@example.org"),
                List.of(Address.of("me@gmail.com"), Address.of("team@example.org")),
                List.of(Address.of("boss@example.org")));
        ReplyAddressing.ReplyTargets targets =
                ReplyAddressing.targets(account("me@gmail.com"), message, false);
        assertEquals(List.of("colleague@example.org"), emails(targets.to()));
        assertEquals(List.of(), emails(targets.cc()));
    }

    @Test
    void replyAllReachesSenderToAndCc() {
        Message message = message(Address.of("colleague@example.org"),
                List.of(Address.of("me@gmail.com"), Address.of("team@example.org")),
                List.of(Address.of("boss@example.org")));
        ReplyAddressing.ReplyTargets targets =
                ReplyAddressing.targets(account("me@gmail.com"), message, true);
        assertEquals(List.of("colleague@example.org", "team@example.org"), emails(targets.to()));
        assertEquals(List.of("boss@example.org"), emails(targets.cc()));
    }

    @Test
    void replyToAnOwnMessageGoesToTheOriginalRecipients() {
        Message message = message(Address.of("me@gmail.com"),
                List.of(Address.of("team@example.org"), Address.of("me@gmail.com")),
                List.of(Address.of("boss@example.org")));
        ReplyAddressing.ReplyTargets targets =
                ReplyAddressing.targets(account("me@gmail.com"), message, false);
        assertEquals(List.of("team@example.org"), emails(targets.to()));
        assertEquals(List.of(), emails(targets.cc()));
    }

    @Test
    void selfIsNeverAmongTheRecipients() {
        Message message = message(Address.of("me@gmail.com"),
                List.of(Address.of("ME@GMAIL.COM"), Address.of("team@example.org")),
                List.of(Address.of("Me@Gmail.com")));
        ReplyAddressing.ReplyTargets targets =
                ReplyAddressing.targets(account("me@gmail.com"), message, true);
        assertEquals(List.of("team@example.org"), emails(targets.to()));
        assertEquals(List.of(), emails(targets.cc()));
    }
}
