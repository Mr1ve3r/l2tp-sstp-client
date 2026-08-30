package io.github.evokelektrique.tunnelforge

/**
 * Method channel contract for the profile store (SPEC phase 8).
 *
 * The store lives in Kotlin. Flutter edits profiles through this channel and
 * keeps none of its own: a profile has to be readable when the system starts
 * the service for an always-on tunnel, and no Dart code is running then.
 *
 * Secrets cross the channel only when the user is editing or exporting a
 * profile. A listing never carries one.
 */
object ProfileContract {
    const val METHOD_CHANNEL = "io.github.evokelektrique.tunnelforge/profiles"

    /** Flutter -> host: every stored profile, oldest first. Secretless. */
    const val LIST_PROFILES = "listProfiles"

    /** Flutter -> host: one profile with its secrets, for the editor. */
    const val LOAD_PROFILE = "loadProfile"

    /** Flutter -> host: store a profile and its secrets. */
    const val SAVE_PROFILE = "saveProfile"

    const val DELETE_PROFILE = "deleteProfile"

    /** Flutter -> host: an identifier for a profile that does not exist yet. */
    const val NEW_PROFILE_ID = "newProfileId"

    const val LAST_PROFILE_ID = "lastProfileId"
    const val SET_LAST_PROFILE_ID = "setLastProfileId"

    /**
     * Flutter -> host: hand over the profiles the Flutter layer used to own
     * (SPEC 8.1.3). Runs once; later calls are ignored so that the import
     * cannot undo edits made after it.
     */
    const val IMPORT_LEGACY_PROFILES = "importLegacyProfiles"

    /** Flutter -> host: wrap an export in a password-encrypted container (SPEC 8.1.4). */
    const val SEAL_EXPORT = "sealExport"

    /** Flutter -> host: unwrap a container produced by [SEAL_EXPORT]. */
    const val OPEN_EXPORT = "openExport"

    const val ARG_ID = "id"
    const val ARG_PROFILE = "profile"
    const val ARG_PROFILES = "profiles"
    const val ARG_PASSWORD = "password"
    const val ARG_PSK = "psk"
    const val ARG_PROXY_PASSWORD = "proxyPassword"
    const val ARG_PAYLOAD = "payload"

    /** Keys of one profile map. Mirrored in `lib/features/profiles/domain/profile_models.dart`. */
    const val FIELD_ID = "id"
    const val FIELD_NAME = "displayName"
    const val FIELD_PROTOCOL = "protocol"
    const val FIELD_SERVER = "server"
    const val FIELD_USERNAME = "user"
    const val FIELD_MTU = "mtu"
    const val FIELD_CREATED_AT = "createdAt"
    const val FIELD_DNS_AUTOMATIC = "dnsAutomatic"
    const val FIELD_DNS1_HOST = "dns1Host"
    const val FIELD_DNS1_PROTOCOL = "dns1Protocol"
    const val FIELD_DNS2_HOST = "dns2Host"
    const val FIELD_DNS2_PROTOCOL = "dns2Protocol"
    const val FIELD_PER_APP_MODE = "perAppMode"
    const val FIELD_APP_LIST = "appList"
    const val FIELD_KILL_SWITCH = "killSwitch"
    const val FIELD_AUTO_RECONNECT = "autoReconnect"
    const val FIELD_IPSEC_ENABLED = "ipsecEnabled"
    const val FIELD_LOCAL_IDENTIFIER = "localIdentifier"
    const val FIELD_PHASE1_PROPOSALS = "phase1Proposals"
    const val FIELD_PHASE2_PROPOSALS = "phase2Proposals"
    const val FIELD_PORT = "port"
    const val FIELD_TRUST_POLICY = "trustPolicy"
    const val FIELD_TRUSTED_CERTIFICATE_IDS = "trustedCertificateIds"
    const val FIELD_PINNED_FINGERPRINTS = "pinnedFingerprints"
    const val FIELD_EXPECTED_HOSTNAME = "expectedHostname"
    const val FIELD_MIN_TLS_VERSION = "minTlsVersion"
    const val FIELD_PPP_AUTH_METHODS = "pppAuthMethods"
    const val FIELD_PROXY_ENABLED = "proxyEnabled"
    const val FIELD_PROXY_HOST = "proxyHost"
    const val FIELD_PROXY_PORT = "proxyPort"
    const val FIELD_PROXY_USERNAME = "proxyUsername"

    /** Error codes returned to Flutter. */
    const val ERROR_BAD_ARGS = "bad_args"
    const val ERROR_STORE_FAILED = "profile_store_failed"
    const val ERROR_BAD_PASSWORD = "profile_container_password"
}
