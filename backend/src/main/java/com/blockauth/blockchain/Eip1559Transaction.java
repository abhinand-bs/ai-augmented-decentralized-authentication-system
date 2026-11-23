package com.blockauth.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.rlp.RlpEncoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;
import org.web3j.utils.Numeric;

/**
 * An EIP-1559 (type 2) transaction, signed. The Web3j 5.0.0 release this project uses predates the final
 * EIP-1559 format, so the encoding is done here with Web3j's RLP and signing primitives:
 * {@code 0x02 || rlp([chainId, nonce, maxPriorityFeePerGas, maxFeePerGas, gasLimit, to, value, data,
 * accessList, yParity, r, s])}, signed over {@code keccak256(0x02 || rlp([... accessList]))}.
 * Cross-checked against ethers v6 in Eip1559TransactionTest.
 */
public record Eip1559Transaction(long chainId, BigInteger nonce, BigInteger maxPriorityFeePerGas,
		BigInteger maxFeePerGas, BigInteger gasLimit, String to, BigInteger value, String data) {

	private static final byte TYPE = 0x02;

	/** @return the signed transaction as 0x-prefixed hex, ready for eth_sendRawTransaction */
	public String sign(Credentials credentials) {
		Sign.SignatureData signature = Sign.signMessage(typed(fields()), credentials.getEcKeyPair(), true);
		int yParity = (signature.getV()[0] & 0xff) - 27;
		List<RlpType> signed = new ArrayList<>(fields());
		signed.add(RlpString.create(yParity));
		signed.add(RlpString.create(Numeric.toBigInt(signature.getR())));
		signed.add(RlpString.create(Numeric.toBigInt(signature.getS())));
		return Numeric.toHexString(typed(signed));
	}

	/** The transaction hash a node reports for this signed transaction. */
	public static String hashOf(String signedHex) {
		return Hash.sha3(signedHex);
	}

	private List<RlpType> fields() {
		return List.of(RlpString.create(chainId), RlpString.create(nonce), RlpString.create(maxPriorityFeePerGas),
				RlpString.create(maxFeePerGas), RlpString.create(gasLimit),
				RlpString.create(Numeric.hexStringToByteArray(to)), RlpString.create(value),
				RlpString.create(Numeric.hexStringToByteArray(data)), new RlpList());
	}

	private static byte[] typed(List<RlpType> values) {
		byte[] rlp = RlpEncoder.encode(new RlpList(values));
		byte[] out = new byte[rlp.length + 1];
		out[0] = TYPE;
		System.arraycopy(rlp, 0, out, 1, rlp.length);
		return out;
	}

}
