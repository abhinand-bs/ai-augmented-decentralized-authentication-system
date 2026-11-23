const { expect } = require('chai');
const { ethers } = require('hardhat');
const { loadFixture, time } = require('@nomicfoundation/hardhat-network-helpers');

const DELAY = 600n;

// Every refusal path that the main suite does not reach, one per check, so branch coverage is complete.
describe('WalletRegistry refusals', function () {
  async function deploy() {
    const [operator, guardian, wallet, stranger] = await ethers.getSigners();
    const registry = await ethers.deployContract('WalletRegistry', [guardian.address, DELAY]);
    await registry.registerWallet(wallet.address);
    await registry.revokeWallet(wallet.address);
    return { registry, operator, guardian, wallet, stranger };
  }

  it('requestActivation: only the operator, never while paused, never for the zero address', async function () {
    const { registry, guardian, wallet, stranger } = await loadFixture(deploy);
    await expect(registry.connect(stranger).requestActivation(wallet.address))
      .to.be.revertedWithCustomError(registry, 'NotOperator');
    await expect(registry.connect(guardian).requestActivation(wallet.address))
      .to.be.revertedWithCustomError(registry, 'NotOperator');
    await expect(registry.requestActivation(ethers.ZeroAddress)).to.be.revertedWithCustomError(registry, 'ZeroAddress');
    await registry.pause();
    await expect(registry.requestActivation(wallet.address)).to.be.revertedWithCustomError(registry, 'RegistryPaused');
  });

  it('pause: not by a stranger, and not twice', async function () {
    const { registry, guardian, stranger } = await loadFixture(deploy);
    await expect(registry.connect(stranger).pause()).to.be.revertedWithCustomError(registry, 'NotOperatorOrGuardian');
    await registry.connect(guardian).pause();
    await expect(registry.pause()).to.be.revertedWithCustomError(registry, 'RegistryPaused');
  });

  it('requestUnpause: not twice; executeUnpause: only the operator and only while paused', async function () {
    const { registry, guardian, stranger } = await loadFixture(deploy);
    await expect(registry.executeUnpause()).to.be.revertedWithCustomError(registry, 'NotPaused');
    await registry.pause();
    expect(await registry.unpauseReadyAt()).to.equal(0n);
    await registry.requestUnpause();
    const readyAt = await registry.unpauseReadyAt();
    expect(readyAt).to.equal(BigInt(await time.latest()) + DELAY);
    await expect(registry.requestUnpause()).to.be.revertedWithCustomError(registry, 'AlreadyRequested');
    await time.increase(DELAY);
    await expect(registry.connect(guardian).executeUnpause()).to.be.revertedWithCustomError(registry, 'NotOperator');
    await expect(registry.connect(stranger).executeUnpause()).to.be.revertedWithCustomError(registry, 'NotOperator');
    await registry.executeUnpause();
    expect(await registry.paused()).to.equal(false);
  });

  it('cancelUnpause: not by a stranger, and only when one is waiting', async function () {
    const { registry, guardian, stranger } = await loadFixture(deploy);
    await expect(registry.cancelUnpause()).to.be.revertedWithCustomError(registry, 'NotRequested');
    await registry.pause();
    await registry.requestUnpause();
    await expect(registry.connect(stranger).cancelUnpause())
      .to.be.revertedWithCustomError(registry, 'NotOperatorOrGuardian');
    await expect(registry.connect(guardian).cancelUnpause()).to.emit(registry, 'UnpauseCancelled')
      .withArgs(guardian.address);
    expect(await registry.unpauseReadyAt()).to.equal(0n);
  });

  it('setGuardian: only the guardian, never zero, never the operator', async function () {
    const { registry, operator, guardian, stranger } = await loadFixture(deploy);
    await expect(registry.setGuardian(stranger.address)).to.be.revertedWithCustomError(registry, 'NotGuardian');
    await expect(registry.connect(guardian).setGuardian(ethers.ZeroAddress))
      .to.be.revertedWithCustomError(registry, 'ZeroAddress');
    await expect(registry.connect(guardian).setGuardian(operator.address))
      .to.be.revertedWithCustomError(registry, 'SameKey');
    await expect(registry.connect(guardian).setGuardian(stranger.address))
      .to.emit(registry, 'GuardianChanged').withArgs(guardian.address, stranger.address);
    expect(await registry.guardian()).to.equal(stranger.address);
  });

  it('delay bounds: the largest delay is accepted, and a request whose end would overflow reverts', async function () {
    const [, guardian, wallet] = await ethers.getSigners();
    const max = await ethers.deployContract('WalletRegistry', [guardian.address, 2n ** 64n - 1n]);
    expect(await max.delay()).to.equal(2n ** 64n - 1n);
    await max.registerWallet(wallet.address);
    await max.revokeWallet(wallet.address);
    // now + delay does not fit in uint64: checked arithmetic reverts instead of wrapping to an early time.
    await expect(max.requestActivation(wallet.address)).to.be.revertedWithPanic(0x11);
    await max.pause();
    await expect(max.requestUnpause()).to.be.revertedWithPanic(0x11);
  });
});
