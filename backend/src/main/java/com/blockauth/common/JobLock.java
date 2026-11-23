package com.blockauth.common;

import java.time.Duration;

/**
 * Runs a scheduled job on one instance at a time. Without Redis there is one instance and the job simply runs;
 * with Redis a lease decides (see RedisLeases).
 */
public interface JobLock {

	JobLock LOCAL = (job, ttl, task) -> {
		task.run();
		return true;
	};

	/** @return whether this instance ran the job (false: another instance holds it, or the lease store is down) */
	boolean runExclusive(String job, Duration ttl, Runnable task);

}
