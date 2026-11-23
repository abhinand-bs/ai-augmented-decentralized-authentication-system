const { expect } = require('chai');
const { ethers } = require('hardhat');
const fc = require('fast-check');
const { loadFixture, time } = require('@nomicfoundation/hardhat-network-helpers');

// Model-based test: random sequences of calls from the operator, the guardian, a stranger and a spare key,
// with time passing between them, run against the contract and against a small JavaScript reference model.
// After every step the contract must agree with the model on whether the call succeeded and on all its state,
// and the stated invariants must hold.
const DELAY = 600;
const NONE = 0;
const ACTIVE = 1;
const REVOKED = 2;
const FUNCTIONS = ['registerWallet', 'revokeWallet', 'requestActivation', 'executeActivation', 'cancelActivation',
  'pause', 'requestUnpause', 'executeUnpause', 'cancelUnpause', 'setOperator', 'setGuardian', 'sendEth'];
const STEPS = [0, 1, DELAY - 1, DELAY, DELAY + 50];

describe('WalletRegistry model-based (fast-check)', function () {
  this.timeout(300000);

  async function deploy() {
    const signers = await ethers.getSigners();
    const keys = signers.slice(0, 4); // operator, guardian, stranger, spare
    const wallets = signers.slice(4, 7).map((s) => s.address);
    const registry = await ethers.deployContract('WalletRegistry', [keys[1].address, DELAY]);
    return { registry, keys, wallets, start: await time.latest() };
  }

  function newModel(keys, start) {
    return {
      now: start, operator: keys[0].address, guardian: keys[1].address, paused: false, epoch: 0,
      wallets: {}, activation: {}, unpause: null, everRegistered: new Set(),
    };
  }

  const live = (m, request) => request !== null && request !== undefined && request.epoch === m.epoch;

  /** Applies the call to the model. @returns false when the contract must revert (the model is then unchanged). */
  function apply(m, fn, from, arg) {
    const isOp = from === m.operator;
    const isGuard = from === m.guardian;
    const status = m.wallets[arg]?.status ?? NONE;
    const zero = arg === ethers.ZeroAddress;
    switch (fn) {
      case 'registerWallet':
        if (!isOp || m.paused || zero || status !== NONE) return false;
        m.wallets[arg] = { status: ACTIVE, registeredAt: m.now };
        m.everRegistered.add(arg);
        return true;
      case 'revokeWallet':
        if (!(isOp || isGuard) || zero || status !== ACTIVE) return false;
        m.wallets[arg].status = REVOKED;
        delete m.activation[arg];
        return true;
      case 'requestActivation':
        if (!isOp || m.paused || zero || status !== REVOKED || live(m, m.activation[arg])) return false;
        m.activation[arg] = { executeAfter: m.now + DELAY, epoch: m.epoch };
        return true;
      case 'executeActivation':
        if (!isOp || m.paused || zero || !live(m, m.activation[arg]) || m.now < m.activation[arg].executeAfter) return false;
        m.wallets[arg].status = ACTIVE;
        delete m.activation[arg];
        return true;
      case 'cancelActivation':
        if (!(isOp || isGuard) || !live(m, m.activation[arg])) return false;
        delete m.activation[arg];
        return true;
      case 'pause':
        if (!(isOp || isGuard) || m.paused) return false;
        m.paused = true;
        m.unpause = null;
        return true;
      case 'requestUnpause':
        if (!isOp || !m.paused || live(m, m.unpause)) return false;
        m.unpause = { executeAfter: m.now + DELAY, epoch: m.epoch };
        return true;
      case 'executeUnpause':
        if (!isOp || !m.paused || !live(m, m.unpause) || m.now < m.unpause.executeAfter) return false;
        m.paused = false;
        m.unpause = null;
        return true;
      case 'cancelUnpause':
        if (!(isOp || isGuard) || !live(m, m.unpause)) return false;
        m.unpause = null;
        return true;
      case 'setOperator':
        if (!isGuard || zero || arg === m.guardian) return false;
        m.operator = arg;
        m.epoch += 1;
        return true;
      case 'setGuardian':
        if (!isGuard || zero || arg === m.operator) return false;
        m.guardian = arg;
        return true;
      case 'sendEth':
        return false;
      default:
        throw new Error(fn);
    }
  }

  async function checkAgrees(registry, m, wallets) {
    expect(await registry.operator()).to.equal(m.operator);
    expect(await registry.guardian()).to.equal(m.guardian);
    expect(await registry.paused()).to.equal(m.paused);
    expect(await registry.unpauseReadyAt()).to.equal(BigInt(live(m, m.unpause) ? m.unpause.executeAfter : 0));
    expect(await ethers.provider.getBalance(await registry.getAddress())).to.equal(0n); // never holds ETH
    for (const w of wallets) {
      const expected = m.wallets[w];
      const [registered, active, registeredAt] = await registry.getWalletStatus(w);
      expect(registered).to.equal(expected !== undefined);
      expect(active).to.equal(expected?.status === ACTIVE);
      expect(registeredAt).to.equal(BigInt(expected?.registeredAt ?? 0));
      if (m.everRegistered.has(w)) expect(registered).to.equal(true); // never back to None
      expect(await registry.activationReadyAt(w))
        .to.equal(BigInt(live(m, m.activation[w]) ? m.activation[w].executeAfter : 0));
    }
  }

  it('agrees with the reference model on every random sequence, and the invariants hold', async function () {
    const step = fc.record({
      fn: fc.constantFrom(...FUNCTIONS),
      caller: fc.integer({ min: 0, max: 3 }),
      target: fc.integer({ min: 0, max: 7 }), // wallet 0-2, key 0-3, or the zero address (7)
      wait: fc.constantFrom(...STEPS),
    });
    let runs = 0;
    await fc.assert(
      fc.asyncProperty(fc.array(step, { minLength: 1, maxLength: 30 }), async (steps) => {
        runs += 1;
        const { registry, keys, wallets, start } = await loadFixture(deploy);
        const m = newModel(keys, start);
        const registeredAtOnce = {};
        for (const { fn, caller, target, wait } of steps) {
          m.now += wait + 1;
          await time.setNextBlockTimestamp(m.now);
          const from = keys[caller];
          let arg;
          if (fn === 'setOperator' || fn === 'setGuardian') {
            arg = target < 4 ? keys[target].address : ethers.ZeroAddress;
          } else {
            arg = target < 3 ? wallets[target] : target === 7 ? ethers.ZeroAddress : wallets[target % 3];
          }
          const expected = apply(m, fn, from.address, arg);
          let ok = true;
          try {
            if (fn === 'sendEth') {
              await (await from.sendTransaction({ to: await registry.getAddress(), value: 1n })).wait();
            } else if (['pause', 'requestUnpause', 'executeUnpause', 'cancelUnpause'].includes(fn)) {
              await (await registry.connect(from)[fn]()).wait();
            } else {
              await (await registry.connect(from)[fn](arg)).wait();
            }
          } catch {
            // Refused before a block was mined; the next step sets a later timestamp anyway.
            ok = false;
          }
          expect(ok, `${fn} from key ${caller} on ${arg}`).to.equal(expected);
          await checkAgrees(registry, m, wallets);
          for (const w of wallets) {
            if (m.wallets[w]) {
              registeredAtOnce[w] ??= m.wallets[w].registeredAt;
              expect(m.wallets[w].registeredAt).to.equal(registeredAtOnce[w]); // registeredAt never changes
            }
          }
        }
      }),
      { numRuns: Number(process.env.FC_RUNS || 60), seed: Number(process.env.FC_SEED || 20261123) },
    );
    expect(runs).to.be.greaterThan(0);
  });
});
