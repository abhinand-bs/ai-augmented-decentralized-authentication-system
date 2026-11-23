package com.blockauth.auth;

/**
 * Actions that need a fresh wallet signature on top of the session ("step-up"). The label is the text
 * the wallet shows; together with the action's parameters it is part of the signed message.
 */
public enum StepUpAction {

	REVOKE_WALLET("Revoke wallet", true),
	ACTIVATE_WALLET("Reactivate wallet after the waiting period", true),
	RESET_SECOND_STEP("Reset the second step of wallet after the waiting period", true),
	FLAG_WALLET("Flag wallet as suspicious", true),
	UNFLAG_WALLET("Remove the suspicious flag from wallet", true),
	START_EMERGENCY_STOP("Switch on the emergency stop", true),
	LIFT_EMERGENCY_STOP("Lift the emergency stop after the waiting period", true),
	CREATE_API_KEY("Create a read-only API key", true),
	REVOKE_API_KEY("Revoke the API key", true),
	CANCEL_ADMIN_ACTION("Cancel the waiting administrator action", true),
	ENABLE_SECOND_STEP("Turn on the second step for this wallet", false),
	DISABLE_SECOND_STEP("Turn off the second step for this wallet", false),
	RENEW_BACKUP_CODES("Replace the backup codes of this wallet", false),
	SET_ALERT_EMAIL("Send sign-in alerts for this wallet to the e-mail address", false),
	REMOVE_ALERT_EMAIL("Stop e-mail sign-in alerts for this wallet", false);

	private final String label;
	private final boolean adminOnly;

	StepUpAction(String label, boolean adminOnly) {
		this.label = label;
		this.adminOnly = adminOnly;
	}

	public String label() {
		return label;
	}

	public boolean adminOnly() {
		return adminOnly;
	}

}
