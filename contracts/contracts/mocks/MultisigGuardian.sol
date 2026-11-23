// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @title MultisigGuardian - TEST DOUBLE ONLY, never deploy
/// @notice A minimal m-of-n wallet used by the tests to show that WalletRegistry works when its guardian is
/// a contract (as a Safe would be): it makes no assumption that the guardian is an externally owned account.
/// Owners propose a call; it runs once `threshold` distinct owners have approved it.
contract MultisigGuardian {
    struct Proposal {
        address target;
        bytes data;
        uint256 approvals;
        bool executed;
    }

    mapping(address => bool) public isOwner;
    uint256 public immutable threshold;
    Proposal[] private proposals;
    mapping(uint256 => mapping(address => bool)) public approved;

    error NotOwner();
    error BadThreshold();
    error AlreadyApproved();
    error AlreadyExecuted();
    error CallFailed(bytes reason);

    event Executed(uint256 indexed id);

    constructor(address[] memory owners, uint256 threshold_) {
        if (threshold_ == 0 || threshold_ > owners.length) revert BadThreshold();
        for (uint256 i = 0; i < owners.length; i++) {
            isOwner[owners[i]] = true;
        }
        threshold = threshold_;
    }

    /// @return id of the new proposal; the proposer's approval is counted
    function propose(address target, bytes calldata data) external returns (uint256 id) {
        if (!isOwner[msg.sender]) revert NotOwner();
        proposals.push(Proposal(target, data, 0, false));
        id = proposals.length - 1;
        _approve(id);
    }

    function proposalCount() external view returns (uint256) {
        return proposals.length;
    }

    function approve(uint256 id) external {
        if (!isOwner[msg.sender]) revert NotOwner();
        _approve(id);
    }

    function _approve(uint256 id) private {
        Proposal storage p = proposals[id];
        if (p.executed) revert AlreadyExecuted();
        if (approved[id][msg.sender]) revert AlreadyApproved();
        approved[id][msg.sender] = true;
        p.approvals += 1;
        if (p.approvals >= threshold) {
            p.executed = true;
            (bool ok, bytes memory reason) = p.target.call(p.data);
            if (!ok) revert CallFailed(reason);
            emit Executed(id);
        }
    }
}
