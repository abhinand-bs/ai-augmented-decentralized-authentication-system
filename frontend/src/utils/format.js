const ADDRESS = /^0x[0-9a-fA-F]{40}$/;

/** Convenience check only; the server validates every address again. */
export function isValidAddress(value) {
  return ADDRESS.test(value);
}

/** True while the text could still become an address, so a half-typed one is not flagged as wrong. */
export function isPartialAddress(value) {
  return /^(0|0x[0-9a-fA-F]{0,40})$/.test(value);
}

/** 0x1234567890abcdef... -> 0x1234…cdef */
export function shortAddress(address) {
  return `${address.slice(0, 6)}…${address.slice(-4)}`;
}

/** WALLET_REVOKED -> "Wallet revoked". The acronyms CSRF, IP and API stay upper-case. */
export function label(value) {
  if (!value) return '';
  const text = String(value).replaceAll('_', ' ').toLowerCase();
  return (text.charAt(0).toUpperCase() + text.slice(1)).replace(/\b(csrf|ip|api)\b/gi, (word) => word.toUpperCase());
}

export function formatDate(iso) {
  // The server sends UTC; this shows the browser's local time with its zone, so two people comparing
  // screens in different places can tell the times apart.
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit',
    timeZoneName: 'short',
  });
}
