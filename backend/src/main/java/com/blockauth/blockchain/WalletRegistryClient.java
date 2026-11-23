package com.blockauth.blockchain;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.RawTransactionManager;
import org.web3j.utils.Convert;

import com.blockauth.common.BlockAuthException;
import com.blockauth.common.ErrorCode;
import com.blockauth.config.BlockAuthProperties;
import com.blockauth.config.ChainTxSettings;

/**
 * The only class that talks to the WalletRegistry contract. Reads are {@code eth_call}s; writes are
 * transactions signed by the operator account. The contract gives that account no way to restore access
 * at once: a reactivation or an unpause is requested, waits the contract's delay, and is then carried out.
 */
@Component
public class WalletRegistryClient {

	private static final Logger log = LoggerFactory.getLogger(WalletRegistryClient.class);
	private static final long RECEIPT_POLL_MILLIS = 250;
	private static final BigInteger ONE_GWEI = BigInteger.TEN.pow(9);
	private static final Event ACTIVATION_REQUESTED = new Event("ActivationRequested",
			List.of(new TypeReference<Address>(true) {
			}, new TypeReference<Uint64>() {
			}));

	private final Web3j web3j;
	private final Credentials operator;
	private final String contractAddress;
	private final RawTransactionManager transactionManager;
	private final long chainId;
	private final ChainTxSettings tx;
	private final Duration receiptTimeout;
	// One chain write at a time from this process, so the operator's nonces never collide; waiting is bounded.
	private final ReentrantLock sending = new ReentrantLock(true);
	private final ChainWriteLock distributedLock;
	// For the two fee methods Web3j 5.0.0 does not know (it predates EIP-1559).
	private final Web3jService rpc;

	@Autowired
	public WalletRegistryClient(Web3j web3j, BlockAuthProperties properties, ChainTxSettings tx,
			ChainWriteLock distributedLock) {
		this(web3j, properties, tx, tx.receiptTimeout(), distributedLock);
	}

	public WalletRegistryClient(Web3j web3j, BlockAuthProperties properties) {
		this(web3j, properties, ChainTxSettings.defaults(), ChainTxSettings.defaults().receiptTimeout(),
				ChainWriteLock.NONE);
	}

	WalletRegistryClient(Web3j web3j, BlockAuthProperties properties, Duration receiptTimeout) {
		this(web3j, properties, ChainTxSettings.defaults(), receiptTimeout, ChainWriteLock.NONE);
	}

	WalletRegistryClient(Web3j web3j, BlockAuthProperties properties, ChainTxSettings tx, Duration receiptTimeout,
			ChainWriteLock distributedLock) {
		BlockAuthProperties.Blockchain config = properties.blockchain();
		this.receiptTimeout = receiptTimeout;
		this.web3j = web3j;
		this.operator = Credentials.create(config.operatorPrivateKey());
		this.contractAddress = config.contractAddress();
		this.chainId = config.chainId();
		this.transactionManager = new RawTransactionManager(web3j, operator, config.chainId());
		this.tx = tx;
		this.distributedLock = distributedLock;
		this.rpc = new HttpService(config.rpcUrl());
	}

	public OnChainWalletStatus getWalletStatus(String wallet) {
		Function function = new Function("getWalletStatus", List.of(new Address(wallet)),
				List.of(new TypeReference<Bool>() {
				}, new TypeReference<Bool>() {
				}, new TypeReference<Uint64>() {
				}));
		List<Type> out = call(function);
		boolean registered = (Boolean) out.get(0).getValue();
		boolean active = (Boolean) out.get(1).getValue();
		long registeredAt = ((BigInteger) out.get(2).getValue()).longValueExact();
		return new OnChainWalletStatus(registered, active, registered ? Instant.ofEpochSecond(registeredAt) : null);
	}

	/** @return the transaction hash */
	public String registerWallet(String wallet) {
		return send("registerWallet", wallet);
	}

	/** @return the transaction hash */
	public String revokeWallet(String wallet) {
		return send("revokeWallet", wallet);
	}

	/** Starts the contract's waiting period for reactivating a revoked wallet. @return the transaction hash */
	public String requestActivation(String wallet) {
		return send("requestActivation", wallet);
	}

	/** Reactivates the wallet once its request has waited the contract's delay. @return the transaction hash */
	public String executeActivation(String wallet) {
		return send("executeActivation", wallet);
	}

	/** @return the transaction hash */
	public String cancelActivation(String wallet) {
		return send("cancelActivation", wallet);
	}

	/** Pauses the contract: no registration or reactivation until an unpause has waited its delay. */
	public String pause() {
		return send("pause", List.of());
	}

	public String requestUnpause() {
		return send("requestUnpause", List.of());
	}

	public String executeUnpause() {
		return send("executeUnpause", List.of());
	}

	public String cancelUnpause() {
		return send("cancelUnpause", List.of());
	}

	/** When the waiting reactivation of {@code wallet} can be carried out; null when none is waiting. */
	public Instant activationReadyAt(String wallet) {
		return instantOrNull(readUint64("activationReadyAt", List.of(new Address(wallet))));
	}

	/** When the waiting unpause can be carried out; null when none is waiting. */
	public Instant unpauseReadyAt() {
		return instantOrNull(readUint64("unpauseReadyAt", List.of()));
	}

	public boolean paused() {
		Function function = new Function("paused", List.of(), List.of(new TypeReference<Bool>() {
		}));
		return (Boolean) call(function).get(0).getValue();
	}

	/** The contract's waiting period for reactivations and unpauses. */
	public Duration delay() {
		return Duration.ofSeconds(readUint64("delay", List.of()));
	}

	/** Lowercase address of the contract's operator: the key allowed to send registry transactions. */
	public String contractOperator() {
		return readAddress("operator");
	}

	/** Lowercase address of the contract's guardian, the separate key that can cancel and replace the operator. */
	public String contractGuardian() {
		return readAddress("guardian");
	}

	public long blockNumber() {
		try {
			return web3j.ethBlockNumber().send().getBlockNumber().longValueExact();
		}
		catch (IOException | RuntimeException e) {
			throw unavailable(e);
		}
	}

	/** Wallets for which an ActivationRequested event was emitted in blocks {@code from} to {@code to}. */
	public Set<String> walletsWithActivationRequests(long from, long to) {
		EthFilter filter = new EthFilter(DefaultBlockParameter.valueOf(BigInteger.valueOf(from)),
				DefaultBlockParameter.valueOf(BigInteger.valueOf(to)), contractAddress)
			.addSingleTopic(EventEncoder.encode(ACTIVATION_REQUESTED));
		try {
			EthLog logs = web3j.ethGetLogs(filter).send();
			if (logs.hasError()) {
				throw new BlockAuthException(ErrorCode.BLOCKCHAIN_UNAVAILABLE);
			}
			Set<String> wallets = new LinkedHashSet<>();
			for (EthLog.LogResult<?> result : logs.getLogs()) {
				// eth_getLogs answers with log objects; anything else means the node cannot be trusted here.
				if (!(result.get() instanceof Log log)) {
					throw new BlockAuthException(ErrorCode.BLOCKCHAIN_UNAVAILABLE);
				}
				String topic = log.getTopics().get(1);
				wallets.add(("0x" + topic.substring(topic.length() - 40)).toLowerCase());
			}
			return wallets;
		}
		catch (IOException | RuntimeException e) {
			throw e instanceof BlockAuthException known ? known : unavailable(e);
		}
	}

	private long readUint64(String name, List<Type> arguments) {
		Function function = new Function(name, arguments, List.of(new TypeReference<Uint64>() {
		}));
		return ((BigInteger) call(function).get(0).getValue()).longValueExact();
	}

	private String readAddress(String name) {
		Function function = new Function(name, List.of(), List.of(new TypeReference<Address>() {
		}));
		return ((String) call(function).get(0).getValue()).toLowerCase();
	}

	private static Instant instantOrNull(long epochSeconds) {
		return epochSeconds == 0 ? null : Instant.ofEpochSecond(epochSeconds);
	}

	public String operatorAddress() {
		return operator.getAddress().toLowerCase();
	}

	public long chainId() {
		try {
			return web3j.ethChainId().send().getChainId().longValueExact();
		}
		catch (IOException | RuntimeException e) {
			throw unavailable(e);
		}
	}

	public boolean contractHasCode() {
		try {
			String code = web3j.ethGetCode(contractAddress, DefaultBlockParameterName.LATEST).send().getCode();
			return code != null && code.length() > 2;
		}
		catch (IOException | RuntimeException e) {
			throw unavailable(e);
		}
	}

	@SuppressWarnings("rawtypes")
	private List<Type> call(Function function) {
		try {
			EthCall response = web3j.ethCall(Transaction.createEthCallTransaction(operator.getAddress(),
					contractAddress, FunctionEncoder.encode(function)), DefaultBlockParameterName.LATEST).send();
			if (response.hasError()) {
				throw new BlockAuthException(ErrorCode.BLOCKCHAIN_UNAVAILABLE);
			}
			List<Type> out = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
			if (out.size() != function.getOutputParameters().size()) {
				// Empty result: nothing is deployed at the configured address.
				throw new BlockAuthException(ErrorCode.BLOCKCHAIN_UNAVAILABLE);
			}
			return out;
		}
		catch (IOException | RuntimeException e) {
			throw e instanceof BlockAuthException known ? known : unavailable(e);
		}
	}

	private String send(String functionName, String wallet) {
		return send(functionName, List.of(new Address(wallet)));
	}

	/**
	 * Sends one operator transaction and waits for its receipt. Writes are serialized (in this process, and across
	 * instances when a distributed lock is configured) so the operator's nonces never collide; a request that
	 * cannot get its turn within {@code lockWait} is refused with BLOCKCHAIN_BUSY instead of holding its thread.
	 */
	@SuppressWarnings("rawtypes")
	private String send(String functionName, List<Type> arguments) {
		boolean locked;
		try {
			locked = sending.tryLock(tx.lockWait().toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BlockAuthException(ErrorCode.BLOCKCHAIN_BUSY);
		}
		if (!locked) {
			throw new BlockAuthException(ErrorCode.BLOCKCHAIN_BUSY);
		}
		try (ChainWriteLock.Lease lease = distributedLock.acquire(tx.lockWait())) {
			if (lease == null) {
				throw new BlockAuthException(ErrorCode.BLOCKCHAIN_BUSY);
			}
			return sendLocked(functionName, FunctionEncoder.encode(new Function(functionName, arguments, List.of())));
		}
		finally {
			sending.unlock();
		}
	}

	private String sendLocked(String functionName, String data) {
		String txHash;
		try {
			EthEstimateGas estimate = web3j
					.ethEstimateGas(Transaction.createEthCallTransaction(operator.getAddress(), contractAddress, data))
					.send();
			if (estimate.hasError()) {
				// The contract would revert (e.g. the wallet is already in the target state).
				throw new BlockAuthException(ErrorCode.BLOCKCHAIN_TX_FAILED);
			}
			BigInteger gasLimit = estimate.getAmountUsed().multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN)
					.min(BigInteger.valueOf(tx.gasLimitCap()));
			txHash = submit(functionName, data, gasLimit, Fees.query(web3j, rpc, tx.maxFeeGwei()), 0);
		}
		catch (IOException | RuntimeException e) {
			throw e instanceof BlockAuthException known ? known : unavailable(e);
		}
		TransactionReceipt receipt = awaitReceipt(txHash);
		if (!receipt.isStatusOK()) {
			log.error("{} transaction {} reverted", functionName, txHash);
			throw new BlockAuthException(ErrorCode.BLOCKCHAIN_TX_FAILED);
		}
		return txHash;
	}

	/** Signs with the pending nonce and sends; deals with the node's usual refusals, once each. */
	private String submit(String functionName, String data, BigInteger gasLimit, Fees fees, int retry)
			throws IOException {
		BigInteger nonce = web3j.ethGetTransactionCount(operator.getAddress(), DefaultBlockParameterName.PENDING)
				.send().getTransactionCount();
		String signed = fees.eip1559()
				? new Eip1559Transaction(chainId, nonce, fees.priorityFee(), fees.maxFee(), gasLimit, contractAddress,
						BigInteger.ZERO, data).sign(operator)
				: transactionManager.sign(RawTransaction.createTransaction(nonce, fees.maxFee(), gasLimit,
						contractAddress, BigInteger.ZERO, data));
		String localHash = Eip1559Transaction.hashOf(signed);
		EthSendTransaction sent = web3j.ethSendRawTransaction(signed).send();
		if (!sent.hasError()) {
			return sent.getTransactionHash();
		}
		String message = sent.getError().getMessage() == null ? "" : sent.getError().getMessage().toLowerCase();
		if (message.contains("already known") || message.contains("already imported")) {
			// The node already has this exact transaction: wait for it like any other.
			return localHash;
		}
		if (retry == 0 && message.contains("nonce too low")) {
			log.warn("{}: nonce {} already used, retrying with the next pending nonce", functionName, nonce);
			return submit(functionName, data, gasLimit, fees, 1);
		}
		if (retry == 0 && message.contains("underpriced")) {
			log.warn("{}: fee too low to replace a pending transaction, retrying once with higher fees", functionName);
			return submit(functionName, data, gasLimit, fees.bumped(tx.maxFeeGwei()), 1);
		}
		log.error("{} transaction refused by the node (JSON-RPC error {})", functionName, sent.getError().getCode());
		throw new BlockAuthException(ErrorCode.BLOCKCHAIN_TX_FAILED);
	}

	/**
	 * Fees per gas. On an EIP-1559 chain (one whose blocks have a base fee) the tip is what {@code eth_gasPrice}
	 * suggests above the base fee, and the maximum is twice the base fee plus the tip; otherwise a legacy gas
	 * price. Both are capped; a base fee above the cap refuses the write rather than overpaying.
	 */
	record Fees(boolean eip1559, BigInteger maxFee, BigInteger priorityFee) {

		static Fees query(Web3j web3j, Web3jService rpc, long capGwei) throws IOException {
			BigInteger cap = BigInteger.valueOf(capGwei).multiply(ONE_GWEI);
			BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
			BlockFee latest = new Request<>("eth_getBlockByNumber", List.of("latest", false), rpc, BlockFee.class)
				.send();
			Object base = latest.hasError() || latest.getResult() == null ? null : latest.getResult().get("baseFeePerGas");
			BigInteger baseFee = base instanceof String hex ? Numeric.decodeQuantity(hex) : null;
			if (baseFee == null) {
				return new Fees(false, gasPrice.min(cap), BigInteger.ZERO);
			}
			if (baseFee.compareTo(cap) >= 0) {
				log.error("Base fee {} wei is above the configured cap of {} gwei", baseFee, capGwei);
				throw new BlockAuthException(ErrorCode.BLOCKCHAIN_UNAVAILABLE);
			}
			EthGasPrice suggested = new Request<>("eth_maxPriorityFeePerGas", List.<String>of(), rpc, EthGasPrice.class)
				.send();
			BigInteger tip = (suggested.hasError() || suggested.getResult() == null ? gasPrice.subtract(baseFee)
					: suggested.getGasPrice()).max(ONE_GWEI.divide(BigInteger.TEN));
			BigInteger maxFee = baseFee.shiftLeft(1).add(tip).min(cap);
			return new Fees(true, maxFee, tip.min(maxFee));
		}

		/** The JSON of a block; only its base fee is read. */
		public static class BlockFee extends Response<Map<String, Object>> {
		}

		/** +25 %, within the cap: enough for a node to accept the transaction as a replacement. */
		Fees bumped(long capGwei) {
			BigInteger cap = BigInteger.valueOf(capGwei).multiply(ONE_GWEI);
			BigInteger max = maxFee.multiply(BigInteger.valueOf(5)).divide(BigInteger.valueOf(4)).add(BigInteger.ONE)
				.min(cap);
			BigInteger tip = priorityFee.multiply(BigInteger.valueOf(5)).divide(BigInteger.valueOf(4)).add(BigInteger.ONE)
				.min(max);
			return new Fees(eip1559, max, tip);
		}

	}

	/** The operator account's balance in wei: it pays the gas of every registry transaction. */
	public BigInteger operatorBalanceWei() {
		try {
			return web3j.ethGetBalance(operator.getAddress(), DefaultBlockParameterName.LATEST).send().getBalance();
		}
		catch (IOException | RuntimeException e) {
			throw unavailable(e);
		}
	}

	/** Whether the operator balance is below the configured warning level. */
	public boolean operatorBalanceLow() {
		return operatorBalanceWei().compareTo(Convert.toWei(tx.lowBalanceEth(), Convert.Unit.ETHER).toBigInteger()) < 0;
	}

	private TransactionReceipt awaitReceipt(String txHash) {
		long deadline = System.nanoTime() + receiptTimeout.toNanos();
		try {
			while (true) {
				Optional<TransactionReceipt> receipt = web3j.ethGetTransactionReceipt(txHash).send()
						.getTransactionReceipt();
				if (receipt.isPresent() && confirmed(receipt.get())) {
					return receipt.get();
				}
				if (System.nanoTime() >= deadline) {
					log.error("Transaction {} not confirmed within {}", txHash, receiptTimeout);
					throw new BlockAuthException(ErrorCode.BLOCKCHAIN_TIMEOUT);
				}
				Thread.sleep(RECEIPT_POLL_MILLIS);
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BlockAuthException(ErrorCode.BLOCKCHAIN_TIMEOUT);
		}
		catch (IOException | RuntimeException e) {
			// Web3j reports a non-2xx answer from the node as an unchecked exception.
			throw e instanceof BlockAuthException known ? known : unavailable(e);
		}
	}

	/** The receipt's block plus (confirmations - 1) newer blocks exist. A reverted receipt needs no more waiting. */
	private boolean confirmed(TransactionReceipt receipt) throws IOException {
		if (tx.confirmations() <= 1 || !receipt.isStatusOK()) {
			return true;
		}
		BigInteger latest = web3j.ethBlockNumber().send().getBlockNumber();
		return latest.subtract(receipt.getBlockNumber()).longValueExact() + 1 >= tx.confirmations();
	}

	private static BlockAuthException unavailable(Exception cause) {
		log.error("Blockchain RPC call failed: {}", cause.getClass().getSimpleName());
		return new BlockAuthException(ErrorCode.BLOCKCHAIN_UNAVAILABLE);
	}

}
