package com.blockauth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The learned anomaly model that sits beside the trust-score rules. It is a risk indicator only: in
 * {@code SHADOW} (the default) its score is stored and shown but changes nothing; in {@code ENFORCE} it can
 * lower the rule score by at most {@code maxPenalty} points, never raise it; {@code OFF} skips it.
 *
 * @param threshold anomaly score at which a penalty starts (0.5 is an ordinary sign-in, 1 a clear outlier)
 * @param minRealSamples recorded ordinary sign-ins needed before the model trains on real data instead of
 * the built-in synthetic population
 * @param perWalletCap most recent sign-ins of one wallet used for training, so no single wallet can shape
 * the model
 * @param trainingSchedule cron expression of the scheduled retraining ("-" switches it off)
 */
@ConfigurationProperties("blockauth.security.anomaly")
@Validated
public record AnomalySettings(
		@DefaultValue("SHADOW") @NotNull Mode mode,
		@DefaultValue("0.6") @DecimalMin("0.5") @DecimalMax("0.99") double threshold,
		@DefaultValue("30") @Min(0) @Max(100) int maxPenalty,
		@DefaultValue("200") @Min(10) int minRealSamples,
		@DefaultValue("50") @Min(1) int perWalletCap,
		@DefaultValue("20000") @Min(100) int maxTrainingRows,
		@DefaultValue("2000") @Min(100) @Max(100000) int syntheticSamples,
		@DefaultValue("100") @Min(10) @Max(500) int trees,
		@DefaultValue("256") @Min(16) @Max(4096) int sampleSize,
		@DefaultValue("42") long seed,
		@DefaultValue("0 30 3 * * *") String trainingSchedule) {

	public enum Mode {
		OFF, SHADOW, ENFORCE
	}

}
