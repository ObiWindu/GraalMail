// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

contract PolyMailIdentity {
    struct KeyRecord {
        bytes signingPublicKey;
        bytes encryptionPublicKey;
        uint32 keyVersion;
        uint64 createdAt;
        uint64 expiresAt;
        bool revoked;
    }

    mapping(address => KeyRecord) public keys;
    mapping(bytes32 => address) public usernames;

    event KeyUpdated(address indexed account, uint32 keyVersion);
    event UsernameRegistered(bytes32 indexed usernameHash, address indexed account);

    function registerUsername(bytes32 usernameHash) external {
        require(usernames[usernameHash] == address(0), "taken");
        usernames[usernameHash] = msg.sender;
        emit UsernameRegistered(usernameHash, msg.sender);
    }

    function updateKeys(bytes calldata signingPublicKey, bytes calldata encryptionPublicKey, uint64 expiresAt) external {
        keys[msg.sender] = KeyRecord(signingPublicKey, encryptionPublicKey, keys[msg.sender].keyVersion + 1, uint64(block.timestamp), expiresAt, false);
        emit KeyUpdated(msg.sender, keys[msg.sender].keyVersion);
    }

    function revoke() external {
        keys[msg.sender].revoked = true;
    }
}
