package com.blockauth.blockchain;

import java.time.Duration;

/**
 * Serializes operator transactions across backend instances, so two instances never sign with the same nonce.
 * With one instance (the default) the client's own lock is enough and this does nothing; with Redis enabled a
 * lease in Redis is used (see RedisChainWriteLock).
 */
public interface ChainWriteLock {

	/** Held while one chain write runs. */
	interface Lease extends AutoCloseable {

		@Override
		void close();

	}

	Lease HELD = () -> {
	};

	ChainWriteLock NONE = wait -> HELD;

	/** @return the lease, or null when it could not be obtained within {@code wait} */
	Lease acquire(Duration wait);

}
