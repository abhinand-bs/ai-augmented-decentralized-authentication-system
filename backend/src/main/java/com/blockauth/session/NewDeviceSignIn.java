package com.blockauth.session;

import java.time.Instant;
import java.util.UUID;

/**
 * Published, inside the sign-in's transaction, whenever a sign-in alert is created (a new device for a wallet
 * that already has others). Listeners that send anything do so only after the transaction committed.
 */
public record NewDeviceSignIn(UUID userId, String walletAddress, String deviceLabel, String ip, Instant at) {
}
