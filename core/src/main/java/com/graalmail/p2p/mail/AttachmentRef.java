package com.graalmail.p2p.mail;

public record AttachmentRef(String contentId, String encryptedFilename, String mimeType, long size) {}