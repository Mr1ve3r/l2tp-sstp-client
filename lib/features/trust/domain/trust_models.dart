import 'package:equatable/equatable.dart';

import 'package:tunnel_forge/features/trust/data/trust_contract.dart';

/// How a stored certificate stands against the clock (SPEC 5.9).
enum CertificateExpiry {
  /// Valid, and not close enough to expiry to say anything about it.
  valid,

  /// Expires within [CertificateFields.expiryWarningWindow].
  expiringSoon,

  /// Past its validity window. The server may still be serving it.
  expired,

  /// Its validity window has not started, usually a clock wrong at one end.
  notYetValid,
}

/// How an SSTP profile decides whether to trust the server (SPEC 5.5).
///
/// Which of these a build offers is answered by the host: `insecure` does not
/// exist in a release build.
enum TrustPolicy {
  system('SYSTEM'),
  systemPlusCustom('SYSTEM_PLUS_CUSTOM'),
  customOnly('CUSTOM_ONLY'),
  pinLeaf('PIN_LEAF'),
  insecure('INSECURE');

  const TrustPolicy(this.wireName);

  /// The name the host uses. Matches the Kotlin `TrustPolicy` enum.
  final String wireName;

  static TrustPolicy? tryFromWire(String? name) {
    for (final policy in TrustPolicy.values) {
      if (policy.wireName == name) return policy;
    }
    return null;
  }
}

/// The fields of an X.509 certificate the application displays.
class CertificateFields extends Equatable {
  const CertificateFields({
    required this.id,
    required this.subjectCn,
    required this.subjectDn,
    required this.issuerDn,
    required this.serialNumber,
    required this.notBefore,
    required this.notAfter,
    required this.sha256Fingerprint,
    required this.sha1Fingerprint,
    required this.isCa,
    required this.keyUsage,
    required this.subjectAltNames,
    required this.publicKeyBits,
    required this.signatureAlgorithm,
  });

  /// How far ahead of expiry the UI starts warning. Matches the host.
  static const Duration expiryWarningWindow = Duration(days: 30);

  /// SHA-256 fingerprint, which is also the identity of a stored certificate.
  final String id;
  final String? subjectCn;
  final String subjectDn;
  final String issuerDn;
  final String serialNumber;
  final DateTime notBefore;
  final DateTime notAfter;
  final String sha256Fingerprint;
  final String sha1Fingerprint;
  final bool isCa;
  final String? keyUsage;
  final List<String> subjectAltNames;
  final int? publicKeyBits;
  final String signatureAlgorithm;

  /// Subject and issuer are the same, i.e. the certificate signed itself.
  bool get isSelfSigned => subjectDn == issuerDn;

  /// A name for this certificate when the user has not given it one.
  String get displayName => subjectCn ?? subjectDn;

  /// Where this certificate stands against [now].
  CertificateExpiry expiryAt(DateTime now) {
    if (now.isBefore(notBefore)) return CertificateExpiry.notYetValid;
    if (now.isAfter(notAfter)) return CertificateExpiry.expired;
    if (notAfter.difference(now) < expiryWarningWindow) {
      return CertificateExpiry.expiringSoon;
    }
    return CertificateExpiry.valid;
  }

  /// The fingerprint as `AB:CD:EF:...`, which is how a router shows it.
  static String formatFingerprint(String hex) {
    final pairs = <String>[];
    for (var i = 0; i + 1 < hex.length; i += 2) {
      pairs.add(hex.substring(i, i + 2).toUpperCase());
    }
    return pairs.join(':');
  }

  static CertificateFields fromMap(Map<Object?, Object?> map) {
    return CertificateFields(
      id: map[TrustContract.fieldId]! as String,
      subjectCn: map[TrustContract.fieldSubjectCn] as String?,
      subjectDn: (map[TrustContract.fieldSubjectDn] as String?) ?? '',
      issuerDn: (map[TrustContract.fieldIssuerDn] as String?) ?? '',
      serialNumber: (map[TrustContract.fieldSerialNumber] as String?) ?? '',
      notBefore: _time(map[TrustContract.fieldNotBefore]),
      notAfter: _time(map[TrustContract.fieldNotAfter]),
      sha256Fingerprint:
          (map[TrustContract.fieldSha256] as String?) ??
          (map[TrustContract.fieldId]! as String),
      sha1Fingerprint: (map[TrustContract.fieldSha1] as String?) ?? '',
      isCa: (map[TrustContract.fieldIsCa] as bool?) ?? false,
      keyUsage: map[TrustContract.fieldKeyUsage] as String?,
      subjectAltNames:
          (map[TrustContract.fieldSubjectAltNames] as List<Object?>?)
              ?.whereType<String>()
              .toList(growable: false) ??
          const <String>[],
      publicKeyBits: (map[TrustContract.fieldPublicKeyBits] as num?)?.toInt(),
      signatureAlgorithm:
          (map[TrustContract.fieldSignatureAlgorithm] as String?) ?? '',
    );
  }

  static DateTime _time(Object? value) => DateTime.fromMillisecondsSinceEpoch(
    value is num ? value.toInt() : 0,
    isUtc: true,
  ).toLocal();

  @override
  List<Object?> get props => [id, sha256Fingerprint, notBefore, notAfter];
}

/// A certificate the store holds, with what the application knows about it.
class ServerCertificate extends Equatable {
  const ServerCertificate({
    required this.fields,
    required this.alias,
    required this.importedAt,
    required this.usageCount,
  });

  final CertificateFields fields;

  /// The name the user gave it.
  final String alias;
  final DateTime importedAt;

  /// How many profiles trust this certificate. Shown before a deletion.
  final int usageCount;

  String get id => fields.id;

  static ServerCertificate fromMap(Map<Object?, Object?> map) {
    return ServerCertificate(
      fields: CertificateFields.fromMap(map),
      alias: (map[TrustContract.fieldAlias] as String?) ?? '',
      importedAt: CertificateFields._time(map[TrustContract.fieldImportedAt]),
      usageCount: (map[TrustContract.fieldUsageCount] as num?)?.toInt() ?? 0,
    );
  }

  @override
  List<Object?> get props => [fields, alias, importedAt, usageCount];
}

/// Something worth telling the user about a certificate they are importing.
///
/// The host sends a key and the number the message needs; the wording is
/// chosen here, where the user's language is known (SPEC 5.4).
class CertificateWarning extends Equatable {
  const CertificateWarning({required this.key, this.detail});

  final String key;
  final String? detail;

  static const String expired = 'trust.warning.expired';
  static const String expiringSoon = 'trust.warning.expiring_soon';
  static const String notYetValid = 'trust.warning.not_yet_valid';
  static const String notACertificateAuthority = 'trust.warning.not_a_ca';
  static const String weakKey = 'trust.warning.weak_key';
  static const String weakSignature = 'trust.warning.weak_signature';
  static const String alreadyImported = 'trust.warning.already_imported';

  static CertificateWarning fromMap(Map<Object?, Object?> map) {
    return CertificateWarning(
      key: (map[TrustContract.fieldWarningKey] as String?) ?? '',
      detail: map[TrustContract.fieldWarningDetail] as String?,
    );
  }

  @override
  List<Object?> get props => [key, detail];
}

/// A certificate the user has been offered but has not yet accepted.
class CertificateCandidate extends Equatable {
  const CertificateCandidate({
    required this.fields,
    required this.pem,
    required this.warnings,
    this.chainPosition,
  });

  final CertificateFields fields;

  /// The certificate as it would be stored. Sent back to accept the import.
  final String pem;
  final List<CertificateWarning> warnings;

  /// Position in a chain downloaded from a server: 0 is the leaf. Null for the
  /// other two import paths, where order means nothing.
  final int? chainPosition;

  String get id => fields.id;

  bool get isAlreadyImported =>
      warnings.any((w) => w.key == CertificateWarning.alreadyImported);

  static CertificateCandidate fromMap(Map<Object?, Object?> map) {
    return CertificateCandidate(
      fields: CertificateFields.fromMap(map),
      pem: (map[TrustContract.fieldPem] as String?) ?? '',
      warnings:
          (map[TrustContract.fieldWarnings] as List<Object?>?)
              ?.whereType<Map<Object?, Object?>>()
              .map(CertificateWarning.fromMap)
              .toList(growable: false) ??
          const <CertificateWarning>[],
      chainPosition: (map[TrustContract.fieldChainPosition] as num?)?.toInt(),
    );
  }

  @override
  List<Object?> get props => [fields, pem, warnings, chainPosition];
}

/// A certificate accepted for import, with the name the user gave it.
class CertificateImportRequest extends Equatable {
  const CertificateImportRequest({required this.pem, required this.alias});

  final String pem;
  final String alias;

  Map<String, Object?> toMap() => <String, Object?>{
    TrustContract.argPem: pem,
    TrustContract.argAlias: alias,
  };

  @override
  List<Object?> get props => [pem, alias];
}

/// A store operation that did not work, with the host's code attached.
class TrustFailure implements Exception {
  const TrustFailure(this.code, this.message);

  final String code;
  final String? message;

  @override
  String toString() => 'TrustFailure($code, $message)';
}
