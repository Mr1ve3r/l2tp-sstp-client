import 'package:tunnel_forge/features/trust/data/trust_contract.dart';
import 'package:tunnel_forge/features/trust/domain/trust_models.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';

/// Turns the host's warning and failure keys into sentences.
///
/// The host sends a key and the number the message needs; the wording lives
/// here, on the side that knows the user's language (SPEC 5.4).
abstract final class CertificateTexts {
  static String warning(AppLocalizations l10n, CertificateWarning warning) {
    final detail = warning.detail;
    switch (warning.key) {
      case CertificateWarning.expired:
        return l10n.certificateWarningExpired;
      case CertificateWarning.expiringSoon:
        return l10n.certificateWarningExpiringSoon(_int(detail));
      case CertificateWarning.notYetValid:
        return l10n.certificateWarningNotYetValid;
      case CertificateWarning.notACertificateAuthority:
        return l10n.certificateWarningNotACa;
      case CertificateWarning.weakKey:
        return l10n.certificateWarningWeakKey(_int(detail));
      case CertificateWarning.weakSignature:
        return l10n.certificateWarningWeakSignature(detail ?? '');
      case CertificateWarning.alreadyImported:
        return l10n.certificateWarningAlreadyImported;
      default:
        // An unknown key means the host learned a warning this build does not
        // know how to phrase. Showing the key is ugly but honest, and better
        // than dropping something the user was meant to see.
        return warning.key;
    }
  }

  static String failure(AppLocalizations l10n, TrustFailure failure) {
    switch (failure.code) {
      case TrustContract.errorParseFailed:
      case TrustContract.errorBadArgs:
        return l10n.certificateNothingFound;
      case TrustContract.errorFetchFailed:
        return failure.message ?? l10n.certificateNothingFound;
      case TrustContract.errorReadFailed:
      case TrustContract.errorStoreFailed:
        return l10n.certificateStoreFailed;
      default:
        return failure.message ?? l10n.certificateStoreFailed;
    }
  }

  /// A short line under a certificate's name: its expiry, in words.
  static String expiry(
    AppLocalizations l10n,
    CertificateFields fields,
    DateTime now,
  ) {
    switch (fields.expiryAt(now)) {
      case CertificateExpiry.expired:
        return l10n.certificateExpired;
      case CertificateExpiry.notYetValid:
        return l10n.certificateNotYetValid;
      case CertificateExpiry.expiringSoon:
        return l10n.certificateExpiresInDays(
          fields.notAfter.difference(now).inDays,
        );
      case CertificateExpiry.valid:
        return '${l10n.certificateValidUntil}: ${formatDate(fields.notAfter)}';
    }
  }

  /// A date the way a certificate viewer shows one: unambiguous, no locale.
  static String formatDate(DateTime time) {
    final local = time.toLocal();
    final month = local.month.toString().padLeft(2, '0');
    final day = local.day.toString().padLeft(2, '0');
    return '${local.year}-$month-$day';
  }

  static int _int(String? value) => int.tryParse(value ?? '') ?? 0;
}
