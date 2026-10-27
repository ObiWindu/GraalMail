// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

contract PolyMailNotifications {
    event MailNotice(
        bytes32 indexed inboxTag,
        bytes32 indexed messageId,
        bytes32 contentHash,
        uint64 expiresAt,
        bytes32 networkHint
    );

    function notify(bytes32 inboxTag, bytes32 messageId, bytes32 contentHash, uint64 expiresAt, bytes32 networkHint) external {
        emit MailNotice(inboxTag, messageId, contentHash, expiresAt, networkHint);
    }
}
