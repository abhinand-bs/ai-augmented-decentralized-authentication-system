const { expect } = require('chai');
const { ethers } = require('hardhat');
const { loadFixture, time } = require('@nomicfoundation/hardhat-network-helpers');

const DELAY = 600n;

describe('WalletRegistry', function () {
  async function deploy() {
    const [operator, guardian, wallet, other, newOperator] = await ethers.getSigners();
    const registry = await ethers.deployContract('WalletRegistry', [guardian.address, DELAY]);
    return { registry, operator, guardian, wallet, other, newOperator };
  }

  async function registered() {
    const ctx = await deploy();
    await ctx.registry.registerWallet(ctx.wallet.address);
    return ctx;
  }

  async function revoked() {
    const ctx = await registered();
    await ctx.registry.revokeWallet(ctx.wallet.address);
    return ctx;
  }

  // Requests a reactivation and waits out the delay.
  async function reactivate(registry, wallet) {
    await registry.requestActivation(wallet);
    await time.increase(DELAY);
    return registry.executeActivation(wallet);
  }

  it('CT-01 deployer is the operator; the guardian and the delay are fixed at deployment', async function () {
    const { registry, operator, guardian } = await loadFixture(deploy);
    expect(await registry.operator()).to.equal(operator.address);
    expect(await registry.guardian()).to.equal(guardian.address);
    expect(await registry.delay()).to.equal(DELAY);
    expect(await registry.paused()).to.equal(false);
  });

  it('CT-01b deployment refuses a missing guardian, the operator as its own guardian, and no delay', async function () {
    const [operator, guardian] = await ethers.getSigners();
    const factory = await ethers.getContractFactory('WalletRegistry');
    await expect(factory.deploy(ethers.ZeroAddress, DELAY)).to.be.revertedWithCustomError(factory, 'ZeroAddress');
    await expect(factory.deploy(operator.address, DELAY)).to.be.revertedWithCustomError(factory, 'SameKey');
    await expect(factory.deploy(guardian.address, 0)).to.be.revertedWithCustomError(factory, 'InvalidDelay');
  });

  it('CT-02 operator registers a wallet', async function () {
    const { registry, wallet } = await loadFixture(deploy);
    const tx = await registry.registerWallet(wallet.address);
    const ts = await time.latest();
    await expect(tx).to.emit(registry, 'WalletRegistered').withArgs(wallet.address, ts);
    expect(await registry.isRegistered(wallet.address)).to.equal(true);
    expect(await registry.isActive(wallet.address)).to.equal(true);
    expect(await registry.getWalletStatus(wallet.address)).to.deep.equal([true, true, BigInt(ts)]);
  });

  it('CT-03 only the operator registers: not another account, not the wallet, not the guardian', async function () {
    const { registry, guardian, wallet, other } = await loadFixture(deploy);
    for (const signer of [other, wallet, guardian]) {
      await expect(registry.connect(signer).registerWallet(wallet.address))
        .to.be.revertedWithCustomError(registry, 'NotOperator');
    }
    expect(await registry.isRegistered(wallet.address)).to.equal(false);
  });

  it('CT-04 zero address is rejected by every wallet mutator', async function () {
    const { registry } = await loadFixture(deploy);
    for (const fn of ['registerWallet', 'revokeWallet', 'requestActivation', 'executeActivation']) {
      await expect(registry[fn](ethers.ZeroAddress)).to.be.revertedWithCustomError(registry, 'ZeroAddress');
    }
  });

  it('CT-05 second registration reverts, also after revocation', async function () {
    const { registry, wallet } = await loadFixture(registered);
    await expect(registry.registerWallet(wallet.address))
      .to.be.revertedWithCustomError(registry, 'AlreadyRegistered');
    await registry.revokeWallet(wallet.address);
    await expect(registry.registerWallet(wallet.address))
      .to.be.revertedWithCustomError(registry, 'AlreadyRegistered');
    expect(await registry.isActive(wallet.address)).to.equal(false);
  });

  it('CT-06 revoke takes effect at once, by the operator or the guardian', async function () {
    const { registry, guardian, wallet, other } = await loadFixture(registered);
    const tx = await registry.revokeWallet(wallet.address);
    await expect(tx).to.emit(registry, 'WalletRevoked').withArgs(wallet.address, await time.latest());
    expect(await registry.isActive(wallet.address)).to.equal(false);
    expect(await registry.isRegistered(wallet.address)).to.equal(true);
    await expect(registry.revokeWallet(wallet.address)).to.be.revertedWithCustomError(registry, 'AlreadyRevoked');
    await expect(registry.revokeWallet(other.address)).to.be.revertedWithCustomError(registry, 'NotRegistered');
    await registry.registerWallet(other.address);
    await registry.connect(guardian).revokeWallet(other.address);
    expect(await registry.isActive(other.address)).to.equal(false);
  });

  it('CT-07 reactivation must be requested and can only be carried out after the delay', async function () {
    const { registry, wallet, other } = await loadFixture(revoked);
    await expect(registry.executeActivation(wallet.address)).to.be.revertedWithCustomError(registry, 'NotRequested');
    const request = await registry.requestActivation(wallet.address);
    const readyAt = BigInt(await time.latest()) + DELAY;
    await expect(request).to.emit(registry, 'ActivationRequested').withArgs(wallet.address, readyAt);
    expect(await registry.activationReadyAt(wallet.address)).to.equal(readyAt);
    await expect(registry.requestActivation(wallet.address)).to.be.revertedWithCustomError(registry, 'AlreadyRequested');

    await time.increase(DELAY - 10n);
    await expect(registry.executeActivation(wallet.address)).to.be.revertedWithCustomError(registry, 'TooEarly');
    expect(await registry.isActive(wallet.address)).to.equal(false);

    await time.increase(10n);
    const tx = await registry.executeActivation(wallet.address);
    await expect(tx).to.emit(registry, 'WalletActivated').withArgs(wallet.address, await time.latest());
    expect(await registry.isActive(wallet.address)).to.equal(true);
    expect(await registry.activationReadyAt(wallet.address)).to.equal(0n);
    await expect(registry.requestActivation(wallet.address)).to.be.revertedWithCustomError(registry, 'AlreadyActive');
    await expect(registry.requestActivation(other.address)).to.be.revertedWithCustomError(registry, 'NotRegistered');
  });

  it('CT-08 accounts without a role cannot revoke or reactivate; the wallet cannot free itself', async function () {
    const { registry, wallet, other } = await loadFixture(registered);
    await expect(registry.connect(other).revokeWallet(wallet.address))
      .to.be.revertedWithCustomError(registry, 'NotOperatorOrGuardian');
    expect(await registry.isActive(wallet.address)).to.equal(true);
    await registry.revokeWallet(wallet.address);
    for (const fn of ['requestActivation', 'executeActivation']) {
      await expect(registry.connect(wallet)[fn](wallet.address)).to.be.revertedWithCustomError(registry, 'NotOperator');
    }
    await expect(registry.connect(wallet).cancelActivation(wallet.address))
      .to.be.revertedWithCustomError(registry, 'NotOperatorOrGuardian');
    expect(await registry.isActive(wallet.address)).to.equal(false);
  });

  it('CT-09 unregistered wallet status is empty', async function () {
    const { registry, wallet } = await loadFixture(deploy);
    expect(await registry.getWalletStatus(wallet.address)).to.deep.equal([false, false, 0n]);
    expect(await registry.isRegistered(wallet.address)).to.equal(false);
    expect(await registry.isActive(wallet.address)).to.equal(false);
    expect(await registry.activationReadyAt(wallet.address)).to.equal(0n);
  });

  it('CT-10 registeredAt survives revoke and reactivation', async function () {
    const { registry, wallet } = await loadFixture(registered);
    const [, , registeredAt] = await registry.getWalletStatus(wallet.address);
    await time.increase(100);
    await registry.revokeWallet(wallet.address);
    expect((await registry.getWalletStatus(wallet.address))[2]).to.equal(registeredAt);
    await reactivate(registry, wallet.address);
    expect((await registry.getWalletStatus(wallet.address))[2]).to.equal(registeredAt);
  });

  it('the operator or the guardian can cancel a waiting reactivation; revoking again also clears it', async function () {
    const { registry, guardian, wallet } = await loadFixture(revoked);
    await registry.requestActivation(wallet.address);
    await expect(registry.connect(guardian).cancelActivation(wallet.address))
      .to.emit(registry, 'ActivationCancelled').withArgs(wallet.address, guardian.address);
    await time.increase(DELAY);
    await expect(registry.executeActivation(wallet.address)).to.be.revertedWithCustomError(registry, 'NotRequested');
    await expect(registry.cancelActivation(wallet.address)).to.be.revertedWithCustomError(registry, 'NotRequested');

    await registry.requestActivation(wallet.address);
    await registry.cancelActivation(wallet.address);
    expect(await registry.activationReadyAt(wallet.address)).to.equal(0n);
    expect(await registry.isActive(wallet.address)).to.equal(false);
  });

  it('while paused nothing can register or be reactivated, but revoking still works', async function () {
    const { registry, guardian, wallet, other } = await loadFixture(revoked);
    await registry.registerWallet(other.address);
    await registry.requestActivation(wallet.address);
    await expect(registry.connect(guardian).pause()).to.emit(registry, 'Paused').withArgs(guardian.address);
    await time.increase(DELAY);

    await expect(registry.executeActivation(wallet.address)).to.be.revertedWithCustomError(registry, 'RegistryPaused');
    await expect(registry.registerWallet(guardian.address)).to.be.revertedWithCustomError(registry, 'RegistryPaused');
    await expect(registry.pause()).to.be.revertedWithCustomError(registry, 'RegistryPaused');
    await registry.revokeWallet(other.address);
    expect(await registry.isActive(wallet.address)).to.equal(false);
    expect(await registry.isActive(other.address)).to.equal(false);
  });

  it('lifting a pause also waits, and can be cancelled', async function () {
    const { registry, guardian } = await loadFixture(deploy);
    await expect(registry.requestUnpause()).to.be.revertedWithCustomError(registry, 'NotPaused');
    await registry.pause();
    await expect(registry.connect(guardian).requestUnpause()).to.be.revertedWithCustomError(registry, 'NotOperator');
    await registry.requestUnpause();
    await expect(registry.executeUnpause()).to.be.revertedWithCustomError(registry, 'TooEarly');
    await registry.connect(guardian).cancelUnpause();
    await time.increase(DELAY);
    await expect(registry.executeUnpause()).to.be.revertedWithCustomError(registry, 'NotRequested');

    await registry.requestUnpause();
    await time.increase(DELAY);
    await expect(registry.executeUnpause()).to.emit(registry, 'Unpaused');
    expect(await registry.paused()).to.equal(false);
    expect(await registry.unpauseReadyAt()).to.equal(0n);
  });

  it('a stolen operator key cannot restore access at once and is undone by the guardian', async function () {
    const { registry, operator, guardian, wallet, newOperator } = await loadFixture(revoked);
    // The attacker holds the operator key: reactivation still has to wait, in plain sight.
    await registry.connect(operator).requestActivation(wallet.address);
    await expect(registry.connect(operator).executeActivation(wallet.address))
      .to.be.revertedWithCustomError(registry, 'TooEarly');
    // The attacker cannot lift a pause at once either.
    await registry.connect(guardian).pause();
    await registry.connect(operator).requestUnpause();
    await expect(registry.connect(operator).executeUnpause()).to.be.revertedWithCustomError(registry, 'TooEarly');

    // The guardian replaces the operator: everything the stolen key requested becomes void.
    await expect(registry.connect(guardian).setOperator(newOperator.address))
      .to.emit(registry, 'OperatorChanged').withArgs(operator.address, newOperator.address);
    await time.increase(DELAY);
    await expect(registry.connect(operator).executeActivation(wallet.address))
      .to.be.revertedWithCustomError(registry, 'NotOperator');
    await expect(registry.connect(newOperator).executeUnpause()).to.be.revertedWithCustomError(registry, 'NotRequested');
    expect(await registry.activationReadyAt(wallet.address)).to.equal(0n);
    expect(await registry.paused()).to.equal(true);
    expect(await registry.isActive(wallet.address)).to.equal(false);
    // The new operator can then request a legitimate reactivation in the normal way.
    await registry.connect(newOperator).requestUnpause();
    await time.increase(DELAY);
    await registry.connect(newOperator).executeUnpause();
    await registry.connect(newOperator).requestActivation(wallet.address);
    expect(await registry.activationReadyAt(wallet.address)).to.be.greaterThan(0n);
  });

  it('only the guardian manages the keys, and the two keys stay different', async function () {
    const { registry, operator, guardian, other } = await loadFixture(deploy);
    await expect(registry.connect(operator).setOperator(other.address)).to.be.revertedWithCustomError(registry, 'NotGuardian');
    await expect(registry.connect(operator).setGuardian(operator.address)).to.be.revertedWithCustomError(registry, 'NotGuardian');
    await expect(registry.connect(guardian).setOperator(guardian.address)).to.be.revertedWithCustomError(registry, 'SameKey');
    await expect(registry.connect(guardian).setGuardian(operator.address)).to.be.revertedWithCustomError(registry, 'SameKey');
    await expect(registry.connect(guardian).setOperator(ethers.ZeroAddress)).to.be.revertedWithCustomError(registry, 'ZeroAddress');
    await expect(registry.connect(guardian).setGuardian(other.address))
      .to.emit(registry, 'GuardianChanged').withArgs(guardian.address, other.address);
    await expect(registry.connect(guardian).setOperator(guardian.address)).to.be.revertedWithCustomError(registry, 'NotGuardian');
    expect(await registry.guardian()).to.equal(other.address);
  });

  it('each wallet has its own independent record', async function () {
    const { registry, wallet, other } = await loadFixture(registered);
    await registry.registerWallet(other.address);
    await registry.revokeWallet(wallet.address);
    expect(await registry.isActive(wallet.address)).to.equal(false);
    expect(await registry.isActive(other.address)).to.equal(true);
  });

  it('holds no funds: plain transfers and unknown calls are rejected', async function () {
    const { registry, operator } = await loadFixture(deploy);
    const address = await registry.getAddress();
    await expect(operator.sendTransaction({ to: address, value: 1n })).to.be.reverted;
    await expect(operator.sendTransaction({ to: address, data: '0xdeadbeef' })).to.be.reverted;
    expect(await ethers.provider.getBalance(address)).to.equal(0n);
  });

  it('exposes exactly the intended interface, none of it payable', async function () {
    const { registry } = await loadFixture(deploy);
    const functions = registry.interface.fragments.filter((f) => f.type === 'function');
    expect(functions.filter((f) => f.stateMutability === 'nonpayable').map((f) => f.name).sort()).to.deep.equal([
      'cancelActivation', 'cancelUnpause', 'executeActivation', 'executeUnpause', 'pause', 'registerWallet',
      'requestActivation', 'requestUnpause', 'revokeWallet', 'setGuardian', 'setOperator',
    ]);
    expect(functions.filter((f) => f.stateMutability === 'view').map((f) => f.name).sort()).to.deep.equal([
      'activationReadyAt', 'delay', 'getWalletStatus', 'guardian', 'isActive', 'isRegistered', 'operator',
      'operatorEpoch', 'paused', 'unpauseReadyAt',
    ]);
    expect(functions.filter((f) => f.payable)).to.have.lengthOf(0);
  });

  it('a wallet can never return to the unregistered state', async function () {
    const { registry, wallet } = await loadFixture(registered);
    await registry.revokeWallet(wallet.address);
    expect(await registry.isRegistered(wallet.address)).to.equal(true);
    await reactivate(registry, wallet.address);
    expect(await registry.isRegistered(wallet.address)).to.equal(true);
    await registry.revokeWallet(wallet.address);
    expect((await registry.getWalletStatus(wallet.address)).slice(0, 2)).to.deep.equal([true, false]);
  });

  it('each state change costs constant, small gas (no loops, a fixed number of storage slots)', async function () {
    const { registry, wallet, other } = await loadFixture(deploy);
    const gas = async (tx) => (await (await tx).wait()).gasUsed;
    const first = await gas(registry.registerWallet(wallet.address));
    const second = await gas(registry.registerWallet(other.address));
    const difference = second > first ? second - first : first - second;
    expect(difference).to.be.at.most(240n);
    expect(first).to.be.lessThan(70000n);
    expect(await gas(registry.revokeWallet(wallet.address))).to.be.lessThan(40000n);
    expect(await gas(registry.requestActivation(wallet.address))).to.be.lessThan(60000n);
    await time.increase(DELAY);
    expect(await gas(registry.executeActivation(wallet.address))).to.be.lessThan(40000n);
  });
});
