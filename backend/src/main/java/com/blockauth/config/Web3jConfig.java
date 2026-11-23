package com.blockauth.config;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import com.blockauth.blockchain.ChainWriteLock;
import com.blockauth.common.JobLock;

import okhttp3.OkHttpClient;

@Configuration
public class Web3jConfig {

	private static final Duration RPC_TIMEOUT = Duration.ofSeconds(5);

	@Bean(destroyMethod = "shutdown")
	Web3j web3j(BlockAuthProperties properties) {
		OkHttpClient client = new OkHttpClient.Builder().connectTimeout(RPC_TIMEOUT).readTimeout(RPC_TIMEOUT)
				.writeTimeout(RPC_TIMEOUT).build();
		return Web3j.build(new HttpService(properties.blockchain().rpcUrl(), client));
	}

	/** One instance: the chain client's own lock serializes its writes. With Redis, RedisLeases replaces this. */
	@Bean
	@ConditionalOnProperty(name = "blockauth.redis.enabled", havingValue = "false", matchIfMissing = true)
	ChainWriteLock chainWriteLock() {
		return ChainWriteLock.NONE;
	}

	/** One instance: every scheduled job simply runs. With Redis, RedisLeases replaces this. */
	@Bean
	@ConditionalOnProperty(name = "blockauth.redis.enabled", havingValue = "false", matchIfMissing = true)
	JobLock jobLock() {
		return JobLock.LOCAL;
	}

}
