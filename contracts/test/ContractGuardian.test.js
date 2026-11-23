const { expect } = require('chai');
const { ethers } = require('hardhat');
const { loadFixture, time } = require('@nomicfoundation/hardhat-network-helpers');

const DELAY = 600n;

// The guardian can be a contract (for example a 2-of-3 multisignature wallet): WalletRegistry checks only
// msg.sender, never tx.origin or whether the caller has code.
describe('WalletRegistry with a contract guardian (2-of-3 multisig test double)', function () {
  async function deploy() {
    const [operator, a, b, c, wallet, newOperator, stranger] = await ethers.getSigners();
    const multisig = await ethers.deployContract('MultisigGuardian', [[a.address, b.address, c.address], 2]);
    const registry = await ethers.deployContract('WalletRegistry', [await multisig.getAddress(), DELAY]);
    await registry.registerWallet(wallet.address);
    const call = (name, args = []) => registry.interface.encodeFunctionData(name, args);
    // Runs a guardian call with the approvals of `signers` (the first proposes).
    const asGuardian = async (signers, name, args) => {
      const target = await registry.getAddress();
      await multisig.connect(signers[0]).propose(target, call(name, args));
      const id = (await multisig.proposalCount()) - 1n;
      for (const s of signers.slice(1)) {
        await multisig.connect(s).approve(id);
      }
    };
    return { registry, multisig, operator, a, b, c, wallet, newOperator, stranger, asGuardian, call };
  }

  it('one owner alone cannot act; two owners can pause the registry', async function () {
    const { registry, a, b, asGuardian } = await loadFixture(deploy);
    await asGuardian([a], 'pause');
    expect(await registry.paused()).to.equal(false);
    await asGuardian([a, b], 'pause');
    expect(await registry.paused()).to.equal(true);
  });

  it('the multisig replaces a stolen operator key, voiding its waiting requests', async function () {
    const { registry, operator, a, c, wallet, newOperator, asGuardian } = await loadFixture(deploy);
    await registry.connect(operator).revokeWallet(wallet.address);
    await registry.connect(operator).requestActivation(wallet.address);
    expect(await registry.activationReadyAt(wallet.address)).to.not.equal(0n);

    await asGuardian([c, a], 'setOperator', [newOperator.address]);

    expect(await registry.operator()).to.equal(newOperator.address);
    expect(await registry.activationReadyAt(wallet.address)).to.equal(0n);
    await time.increase(DELAY);
    await expect(registry.connect(operator).executeActivation(wallet.address))
      .to.be.revertedWithCustomError(registry, 'NotOperator');
    await expect(registry.connect(newOperator).executeActivation(wallet.address))
      .to.be.revertedWithCustomError(registry, 'NotRequested');
  });

  it('the multisig can revoke and cancel; its owners have no rights of their own', async function () {
    const { registry, operator, a, b, wallet, asGuardian } = await loadFixture(deploy);
    await expect(registry.connect(a).revokeWallet(wallet.address))
      .to.be.revertedWithCustomError(registry, 'NotOperatorOrGuardian');
    await asGuardian([a, b], 'revokeWallet', [wallet.address]);
    expect(await registry.isActive(wallet.address)).to.equal(false);

    await registry.connect(operator).requestActivation(wallet.address);
    await asGuardian([b, a], 'cancelActivation', [wallet.address]);
    expect(await registry.activationReadyAt(wallet.address)).to.equal(0n);
  });

  it('a stranger cannot use the multisig, and the registry holds no ETH sent with a guardian call', async function () {
    const { registry, multisig, stranger, call } = await loadFixture(deploy);
    await expect(multisig.connect(stranger).propose(await registry.getAddress(), call('pause')))
      .to.be.revertedWithCustomError(multisig, 'NotOwner');
    expect(await ethers.provider.getBalance(await registry.getAddress())).to.equal(0n);
  });
});
