package com.blockauth.alert;

import java.net.InetAddress;
import java.util.Locale;
import java.util.regex.Pattern;

import com.blockauth.common.BlockAuthException;

/** Pure helpers for the alert e-mails: address checks, masking, and making untrusted text safe for a mail body. */
public final class AlertEmails {

	// Deliberately strict: one @, no spaces, quotes, angle brackets or control characters (no header injection).
	private static final Pattern ADDRESS = Pattern.compile("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@"
			+ "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

	private AlertEmails() {
	}

	/** Trimmed and lower-cased; refused (400) unless it is a plain address of at most 254 characters. */
	public static String normalize(String address) {
		String value = address == null ? "" : address.trim();
		if (value.length() > 254 || !ADDRESS.matcher(value).matches()) {
			throw BlockAuthException.invalid("email", "must be a plain e-mail address");
		}
		return value.toLowerCase(Locale.ROOT);
	}

	/** j***@example.com */
	public static String mask(String address) {
		int at = address.indexOf('@');
		return address.charAt(0) + "***" + address.substring(at);
	}

	/** Text from the client (a User-Agent label): no line breaks or other control characters, at most 80 characters. */
	public static String plain(String text) {
		if (text == null) {
			return "unknown";
		}
		String cleaned = text.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ").strip();
		return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
	}

	/** IPv4 with the last octet masked (203.0.113.x); IPv6 to its first three groups (2001:db8:85a3::/48). */
	public static String coarseIp(String ip) {
		if (ip == null || ip.isBlank()) {
			return "unknown";
		}
		try {
			if (!ip.matches("^[0-9a-fA-F:.]+$")) {
				return "unknown";
			}
			byte[] bytes = InetAddress.getByName(ip).getAddress();
			if (bytes.length == 4) {
				return (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + "." + (bytes[2] & 0xff) + ".x";
			}
			return String.format("%x:%x:%x::/48", ((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff),
					((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff), ((bytes[4] & 0xff) << 8) | (bytes[5] & 0xff));
		}
		catch (java.net.UnknownHostException e) {
			return "unknown";
		}
	}

}
