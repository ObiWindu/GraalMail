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

    /// Optional spam-deposit configuration. Zero address disables deposits:
    /// plain `notify` stays free (spec 5.5: no token economics before the
    /// basic lifecycle is reliable).
    address public depositRecipient;
    uint256 public depositAmount;
    address public owner;

    constructor() {
        owner = msg.sender;
    }

    function setDepositPolicy(address recipient, uint256 amount) external {
        require(msg.sender == owner, "not owner");
        depositRecipient = recipient;
        depositAmount = amount;
    }

    function notify(bytes32 inboxTag, bytes32 messageId, bytes32 contentHash, uint64 expiresAt, bytes32 networkHint) external {
        emit MailNotice(inboxTag, messageId, contentHash, expiresAt, networkHint);
    }

    /// Opt-in notification with a spam deposit. The deposit is forwarded to the
    /// configured recipient (refunds, if any, are handled off-chain by policy).
    function notifyWithDeposit(bytes32 inboxTag, bytes32 messageId, bytes32 contentHash, uint64 expiresAt, bytes32 networkHint) external payable {
        require(depositRecipient != address(0), "deposits disabled");
        require(msg.value >= depositAmount, "insufficient deposit");
        (bool ok, ) = depositRecipient.call{value: msg.value}("");
        require(ok, "deposit forward failed");
        emit MailNotice(inboxTag, messageId, contentHash, expiresAt, networkHint);
    }
}
