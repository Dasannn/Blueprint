# 0009 — Updates are signed

- Status: accepted
- Date: 2026-10-03
- Owner decision: the public repository must remain safe for server updates if a GitHub account is compromised.

## Decision

Every downloadable release jar has a detached `<jar-name>.sig` asset containing
the base64 Ed25519 signature over the exact jar bytes. Both `/status update` and
`update.auto-download` require a valid signature before staging into
`plugins/update/`, after the existing size, HTTPS, SHA-256 and plugin-metadata
checks. Signature downloads follow the same HTTPS and redirect rules, with a
hard 1 KiB cap. Availability checks remain unchanged.

The running plugin embeds primary and backup public keys in `update-keys.txt`,
one base64 X.509 SPKI key per line, with `#` comments permitted. Keys load once
from the running jar, never from downloaded metadata or configuration. Either
key may verify a release. With no valid key, updates fail closed with a clear
log message. Missing or invalid signatures delete the temporary jar, leave
staging untouched, log SEVERE with the release name, and report translated
failure text to the requesting administrator. There is no verification bypass.

`tools/SignRelease.java` uses only the JDK. Private keys are password-encrypted
PKCS#8 using PBES2, PBKDF2-HMAC-SHA256 and AES-256, and stay outside git work
trees. Key generation refuses existing key files. Passwords come from the
console without echo, or `SB_SIGN_PASSWORD` when there is no console.

## Rationale and consequences

A release's own checksum cannot establish authenticity: a compromised account
can replace both jar and checksum. Signing authority resides in separately
held private keys. The repository remains public; publishing requires signing
the final bytes locally before uploading jar, checksum and signature.

The primary key is held on the release PC and backed up to USB or a password
manager. The backup signing key is stored separately. A new jar can embed
rotated keys for future updates, but must first be signed by a key trusted by
the currently running jar. If the primary key is lost, sign with the backup.
If both are lost, administrators must manually install one jar containing new
trusted keys. A compromised repository cannot supply replacement trust.

The initial resource contains placeholder comments only. Real primary and
backup public keys must be inserted before releasing; placeholder builds
deliberately cannot stage updates. Existing servers must manually install the
first signing-enforcing release to gain this protection. This decision amends
the updater's former checksum-only trust and warning-only failure description;
it introduces no runtime dependency or architecture change.
