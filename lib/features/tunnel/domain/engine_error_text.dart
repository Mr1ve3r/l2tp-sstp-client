import 'package:tunnel_forge/l10n/app_localizations.dart';

/// Turns an `EngineError.messageKey` into a sentence in the user's language.
///
/// The engines report failures as a key plus a technical detail; the wording
/// lives here, on the side that knows the language (SPEC 9.2). `null` means
/// this build has no sentence for the key, and the caller falls back to the
/// detail — ugly, but better than saying nothing.
String? engineErrorText(AppLocalizations l10n, String? key) {
  return switch (key) {
    'engine.error.network_unreachable' => l10n.engineErrorNetworkUnreachable,
    'engine.error.authentication_failed' =>
      l10n.engineErrorAuthenticationFailed,
    'engine.error.tls_handshake_failed' => l10n.engineErrorTlsHandshakeFailed,
    'engine.error.certificate_rejected' => l10n.engineErrorCertificateRejected,
    'engine.error.certificate_expired' => l10n.engineErrorCertificateExpired,
    'engine.error.hostname_mismatch' => l10n.engineErrorHostnameMismatch,
    'engine.error.ipsec_failed' => l10n.engineErrorIpsecFailed,
    'engine.error.ppp_negotiation_failed' =>
      l10n.engineErrorPppNegotiationFailed,
    'engine.error.timed_out' => l10n.engineErrorTimedOut,
    'engine.error.internal' => l10n.engineErrorInternal,
    _ => null,
  };
}
