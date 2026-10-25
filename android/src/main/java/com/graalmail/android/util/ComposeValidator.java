/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.android.util;

import com.graalmail.core.domain.Address;
import java.util.ArrayList;
import java.util.List;

/**
 * Validation of the compose form. Kept free of Android types so that it can be
 * unit tested on a plain JVM.
 */
public final class ComposeValidator {

    /** A single validation problem. */
    public static final class Problem {
        private final String field;
        private final String message;

        public Problem(String field, String message) {
            this.field = field == null ? "" : field;
            this.message = message == null ? "" : message;
        }

        public String field() {
            return field;
        }

        public String message() {
            return message;
        }

        @Override
        public String toString() {
            return field + ": " + message;
        }
    }

    /** The outcome of a validation run. */
    public static final class Result {
        private final List<Problem> problems;
        private final List<Address> to;
        private final List<Address> cc;
        private final List<Address> bcc;

        Result(List<Problem> problems, List<Address> to, List<Address> cc, List<Address> bcc) {
            this.problems = List.copyOf(problems);
            this.to = List.copyOf(to);
            this.cc = List.copyOf(cc);
            this.bcc = List.copyOf(bcc);
        }

        public boolean isValid() {
            return problems.isEmpty();
        }

        public List<Problem> problems() {
            return problems;
        }

        public List<Address> to() {
            return to;
        }

        public List<Address> cc() {
            return cc;
        }

        public List<Address> bcc() {
            return bcc;
        }

        /** The first problem of a field, or null when the field is valid. */
        public Problem problemFor(String field) {
            for (Problem problem : problems) {
                if (problem.field().equals(field)) {
                    return problem;
                }
            }
            return null;
        }
    }

    private ComposeValidator() {
    }

    /**
     * Validates the compose form.
     *
     * @param to     comma or semicolon separated recipients
     * @param cc     comma or semicolon separated carbon copies
     * @param bcc    comma or semicolon separated blind carbon copies
     * @param body   the plain text body
     */
    public static Result validate(String to, String cc, String bcc, String body) {
        List<Problem> problems = new ArrayList<>();
        List<Address> toAddresses = parse(to);
        List<Address> ccAddresses = parse(cc);
        List<Address> bccAddresses = parse(bcc);
        for (Address address : toAddresses) {
            if (!address.isSyntacticallyValid()) {
                problems.add(new Problem("to", "Invalid address: " + address.format()));
            }
        }
        for (Address address : ccAddresses) {
            if (!address.isSyntacticallyValid()) {
                problems.add(new Problem("cc", "Invalid address: " + address.format()));
            }
        }
        for (Address address : bccAddresses) {
            if (!address.isSyntacticallyValid()) {
                problems.add(new Problem("bcc", "Invalid address: " + address.format()));
            }
        }
        if (toAddresses.isEmpty() && ccAddresses.isEmpty() && bccAddresses.isEmpty()) {
            problems.add(new Problem("to", "Enter at least one recipient."));
        }
        if (body == null || body.isBlank()) {
            problems.add(new Problem("body", "Enter a message body."));
        }
        return new Result(problems, toAddresses, ccAddresses, bccAddresses);
    }

    /** Splits a recipient field into addresses; blank entries are dropped. */
    public static List<Address> parse(String raw) {
        List<Address> addresses = new ArrayList<>();
        if (raw == null) {
            return addresses;
        }
        for (String part : raw.split("[,;]")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            addresses.add(Address.parse(value));
        }
        return addresses;
    }
}
