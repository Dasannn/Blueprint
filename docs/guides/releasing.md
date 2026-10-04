# Signing a release

Use JDK 25 and run commands from the repository root. Claude runs the Maven
build; sign only the final jar after all build and release checks. Never rebuild
or modify the jar after signing. The repository and releases remain public.

## Generate and back up keys

Generate the primary and backup once, using directories **outside every git
work tree**. For example, in PowerShell:

```powershell
java tools/SignRelease.java keygen C:\SocialBlueprint-Signing primary
java tools/SignRelease.java keygen E:\SocialBlueprint-Backup backup
```

Each command reads a nonempty password from the console without echo and
writes `<name>.key` (binary, password-encrypted PKCS#8) and `<name>.pub` (base64
X.509 SPKI). It also prints the public key. Existing files are never overwritten.
Encryption is PBES2 with PBKDF2-HMAC-SHA256 (600,000 iterations, random salt)
and AES-256 with a random IV. Use strong, distinct passwords.

If there is no console, the tool requires `SB_SIGN_PASSWORD` in the process
environment. Supply it through a local secret store and clear it immediately
afterward. Do not put passwords in command arguments, scripts, shell history,
repository files or GitHub Actions secrets: repository/account access must not
also grant signing authority.

Keep the primary `.key` on the release PC, with a tested copy on USB or in a
password manager. Store its password securely and keep it recoverable. Keep
the backup `.key` and password separately from the primary PC and its backup;
do not leave both private keys on the release PC. Public `.pub` files are safe
to publish. Private `.key` files and passwords must never enter the repository
or release assets, even though the private files are encrypted. Restrict local
file access to the release operator.

Replace the two placeholder comments in
`src/main/resources/update-keys.txt` with the primary and backup `.pub` contents,
one base64 key per line. `#` comment lines are allowed. This resource ships
inside the plugin jar; no server configuration can override it. Confirm the
final jar contains both real keys before shipping. With no valid embedded key,
downloads fail closed, though `/status update check` still reports availability.

## Sign and publish

```powershell
java tools/SignRelease.java sign C:\SocialBlueprint-Signing\primary.key target\SocialBlueprint-2.0.2.jar
java tools/SignRelease.java verify C:\SocialBlueprint-Signing\primary.pub target\SocialBlueprint-2.0.2.jar
(Get-FileHash -Algorithm SHA256 target\SocialBlueprint-2.0.2.jar).Hash.ToLower() | Set-Content -Encoding ascii target\SocialBlueprint-2.0.2.jar.sha256
```

`sign` writes `target\SocialBlueprint-2.0.2.jar.sig`, base64 of the Ed25519
signature of the exact jar bytes. `verify` accepts either the `.pub` file or
its base64 contents and exits 0 for a valid signature, 1 otherwise. Confirm
verification succeeds before uploading these three assets together:

- `SocialBlueprint-2.0.2.jar`
- `SocialBlueprint-2.0.2.jar.sha256`
- `SocialBlueprint-2.0.2.jar.sig`

The signature companion name must exactly match the jar name plus `.sig`.
The plugin applies the existing HTTPS/redirect rules and a hard 1 KiB signature
download cap. A missing, corrupt, unknown-key or mismatched signature refuses
the update, deletes the temporary download, logs SEVERE with the release name,
and reports a translated error. Successful verification stages the jar for the
next server restart. Manual and automatic downloads enforce the same checks.

The first signing-enforcing release must be installed manually on servers
running older checksum-only versions; those versions do not gain signature
protection merely because release assets include a signature.

## Move PCs, rotate and recover

To move to a new release PC, securely copy the existing encrypted `.key` files
needed for signing and their `.pub` files from the old PC or tested backup.
Recover the passwords from secure storage and verify a local test signature
before relying on the new PC. Keep the backup key in its separate storage;
moving PCs does not require changing public keys or regenerating private keys.

For rotation, embed the new primary/backup public keys in a future jar and
sign that jar with a key already trusted by the running versions. Only after
servers install it can future updates rely on its new keys. Plan transitions
so servers that skip releases still have a trusted signing key in common.

If the primary is lost, sign the next release with the backup key and embed
replacement keys through that trusted release. If both private keys or their
passwords are lost, there is no automatic recovery: server owners manually
install one jar containing new primary/backup public keys. If a key is exposed,
use the separately held backup to publish a replacement trust set and inform
server owners; a server still running a jar that trusts the exposed key remains
at risk until it updates.
