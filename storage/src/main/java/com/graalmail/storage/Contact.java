/*
 * Licensed under the Apache License, Version 2.0
 * (ObiWindu <Obi.wandu@proton.me>)
 */
package com.graalmail.storage;

import java.time.Instant;

/** A locally stored contact. Contacts never leave the device automatically. */
public final class Contact {

    private final String id;
    private final String name;
    private final String email;
    private final String note;
    private final Instant updatedAt;

    public Contact(String id, String name, String email, String note, Instant updatedAt) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.email = email == null ? "" : email;
        this.note = note == null ? "" : note;
        this.updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public String id() { return id; }
    public String name() { return name; }
    public String email() { return email; }
    public String note() { return note; }
    public Instant updatedAt() { return updatedAt; }
}
