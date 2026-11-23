// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @title WalletRegistry - BlockAuth on-chain identity status registry
/// @notice Two keys guard the registry. The operator (the BlockAuth backend's online key) registers and
/// revokes wallets at once, but anything that gives access back - reactivating a revoked wallet, or
/// lifting a pause - must first be requested and can only be carried out after `delay` seconds, during
/// which the operator or the guardian can cancel it. The guardian (a separate key kept offline, which can
/// itself be a multisignature wallet) can also pause the registry and revoke wallets, and is the only key
/// that can replace the operator. A stolen operator key therefore cannot restore access without a
/// visible, cancellable waiting period, cannot undo a pause at once, and can be replaced; replacing it
/// voids every request it left behind.
contract WalletRegistry {
    enum Status { None, Active, Revoked }

    struct WalletRecord {
        Status status;
        uint64 registeredAt;
    }

    struct Request {
        uint64 executeAfter;
        uint32 epoch;
    }

    address public operator;
    address public guardian;
    uint64 public immutable delay;
    bool public paused;
    /// @notice Increases whenever the operator is replaced; requests from an earlier epoch are void.
    uint32 public operatorEpoch;

    mapping(address => WalletRecord) private records;
    mapping(address => Request) private activationRequests;
    Request private unpauseRequest;

    event WalletRegistered(address indexed wallet, uint64 registeredAt);
    event WalletRevoked(address indexed wallet, uint64 revokedAt);
    event WalletActivated(address indexed wallet, uint64 activatedAt);
    event ActivationRequested(address indexed wallet, uint64 executeAfter);
    event ActivationCancelled(address indexed wallet, address indexed by);
    event Paused(address indexed by);
    event UnpauseRequested(uint64 executeAfter);
    event UnpauseCancelled(address indexed by);
    event Unpaused(address indexed by);
    event OperatorChanged(address indexed previousOperator, address indexed newOperator);
    event GuardianChanged(address indexed previousGuardian, address indexed newGuardian);

    error NotOperator();
    error NotGuardian();
    error NotOperatorOrGuardian();
    error ZeroAddress();
    error SameKey();
    error InvalidDelay();
    error AlreadyRegistered();
    error NotRegistered();
    error AlreadyRevoked();
    error AlreadyActive();
    error RegistryPaused();
    error NotPaused();
    error AlreadyRequested();
    error NotRequested();
    error TooEarly();

    modifier onlyOperator() {
        if (msg.sender != operator) revert NotOperator();
        _;
    }

    modifier onlyGuardian() {
        if (msg.sender != guardian) revert NotGuardian();
        _;
    }

    modifier onlyOperatorOrGuardian() {
        if (msg.sender != operator && msg.sender != guardian) revert NotOperatorOrGuardian();
        _;
    }

    modifier nonZero(address wallet) {
        if (wallet == address(0)) revert ZeroAddress();
        _;
    }

    modifier whenNotPaused() {
        if (paused) revert RegistryPaused();
        _;
    }

    /// @param guardian_ the offline key; must differ from the deploying operator
    /// @param delay_ seconds a reactivation or an unpause must wait before it can be carried out
    constructor(address guardian_, uint64 delay_) nonZero(guardian_) {
        if (guardian_ == msg.sender) revert SameKey();
        if (delay_ == 0) revert InvalidDelay();
        operator = msg.sender;
        guardian = guardian_;
        delay = delay_;
    }

    // ---------------------------------------------------------------- wallets

    function registerWallet(address wallet) external onlyOperator whenNotPaused nonZero(wallet) {
        if (records[wallet].status != Status.None) revert AlreadyRegistered();
        uint64 ts = uint64(block.timestamp);
        records[wallet] = WalletRecord(Status.Active, ts);
        emit WalletRegistered(wallet, ts);
    }

    /// @notice Takes effect at once, also while paused: cutting access off must never wait.
    function revokeWallet(address wallet) external onlyOperatorOrGuardian nonZero(wallet) {
        Status s = records[wallet].status;
        if (s == Status.None) revert NotRegistered();
        if (s == Status.Revoked) revert AlreadyRevoked();
        records[wallet].status = Status.Revoked;
        delete activationRequests[wallet];
        emit WalletRevoked(wallet, uint64(block.timestamp));
    }

    function requestActivation(address wallet) external onlyOperator whenNotPaused nonZero(wallet) {
        Status s = records[wallet].status;
        if (s == Status.None) revert NotRegistered();
        if (s == Status.Active) revert AlreadyActive();
        if (_live(activationRequests[wallet])) revert AlreadyRequested();
        uint64 executeAfter = uint64(block.timestamp) + delay;
        activationRequests[wallet] = Request(executeAfter, operatorEpoch);
        emit ActivationRequested(wallet, executeAfter);
    }

    function executeActivation(address wallet) external onlyOperator whenNotPaused nonZero(wallet) {
        Request memory request = activationRequests[wallet];
        if (!_live(request)) revert NotRequested();
        // The delay is minutes to days; the few seconds a block producer can shift the timestamp do not matter.
        // slither-disable-next-line timestamp
        if (block.timestamp < request.executeAfter) revert TooEarly();
        // A live request exists only for a revoked wallet: revoking deletes it, and it is made only then.
        records[wallet].status = Status.Active;
        delete activationRequests[wallet];
        emit WalletActivated(wallet, uint64(block.timestamp));
    }

    function cancelActivation(address wallet) external onlyOperatorOrGuardian {
        if (!_live(activationRequests[wallet])) revert NotRequested();
        delete activationRequests[wallet];
        emit ActivationCancelled(wallet, msg.sender);
    }

    // ---------------------------------------------------------------- pause

    function pause() external onlyOperatorOrGuardian {
        if (paused) revert RegistryPaused();
        paused = true;
        delete unpauseRequest;
        emit Paused(msg.sender);
    }

    function requestUnpause() external onlyOperator {
        if (!paused) revert NotPaused();
        if (_live(unpauseRequest)) revert AlreadyRequested();
        uint64 executeAfter = uint64(block.timestamp) + delay;
        unpauseRequest = Request(executeAfter, operatorEpoch);
        emit UnpauseRequested(executeAfter);
    }

    function executeUnpause() external onlyOperator {
        if (!paused) revert NotPaused();
        if (!_live(unpauseRequest)) revert NotRequested();
        // Same as executeActivation: the delay dwarfs any timestamp drift.
        // slither-disable-next-line timestamp
        if (block.timestamp < unpauseRequest.executeAfter) revert TooEarly();
        paused = false;
        delete unpauseRequest;
        emit Unpaused(msg.sender);
    }

    function cancelUnpause() external onlyOperatorOrGuardian {
        if (!_live(unpauseRequest)) revert NotRequested();
        delete unpauseRequest;
        emit UnpauseCancelled(msg.sender);
    }

    // ---------------------------------------------------------------- keys

    /// @notice Replaces a lost or stolen operator key and voids every request the old one made.
    function setOperator(address newOperator) external onlyGuardian nonZero(newOperator) {
        if (newOperator == guardian) revert SameKey();
        emit OperatorChanged(operator, newOperator);
        operator = newOperator;
        operatorEpoch += 1;
    }

    function setGuardian(address newGuardian) external onlyGuardian nonZero(newGuardian) {
        if (newGuardian == operator) revert SameKey();
        emit GuardianChanged(guardian, newGuardian);
        guardian = newGuardian;
    }

    // ---------------------------------------------------------------- views

    // Slither flags the enum comparisons below because the record also holds a timestamp; nothing here
    // compares a time or a balance.
    function isRegistered(address wallet) external view returns (bool) {
        // slither-disable-next-line timestamp
        return records[wallet].status != Status.None;
    }

    function isActive(address wallet) external view returns (bool) {
        // slither-disable-next-line incorrect-equality,timestamp
        return records[wallet].status == Status.Active;
    }

    function getWalletStatus(address wallet)
        external
        view
        returns (bool registered, bool active, uint64 registeredAt)
    {
        WalletRecord memory r = records[wallet];
        // slither-disable-next-line incorrect-equality,timestamp
        return (r.status != Status.None, r.status == Status.Active, r.registeredAt);
    }

    /// @return when the waiting reactivation of `wallet` can be carried out; 0 when none is waiting
    function activationReadyAt(address wallet) external view returns (uint64) {
        Request memory request = activationRequests[wallet];
        return _live(request) ? request.executeAfter : 0;
    }

    /// @return when the waiting unpause can be carried out; 0 when none is waiting
    function unpauseReadyAt() external view returns (uint64) {
        return _live(unpauseRequest) ? unpauseRequest.executeAfter : 0;
    }

    // An epoch counter and a "not set" check, not a time comparison.
    function _live(Request memory request) private view returns (bool) {
        // slither-disable-next-line incorrect-equality,timestamp
        return request.executeAfter != 0 && request.epoch == operatorEpoch;
    }
}
