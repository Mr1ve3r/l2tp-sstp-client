import 'package:tunnel_forge/features/trust/data/trust_contract.dart';

/// Certificate maps shaped exactly as the host sends them.
///
/// Kept in one place so a change to the channel's shape is one edit here and
/// not a hunt through the trust tests.
abstract final class CertificateFixtures {
  static const String caFingerprint =
      'aa11bb22cc33dd44ee55ff6677889900aabbccddeeff00112233445566778899';
  static const String leafFingerprint =
      '1122334455667788990011223344556677889900112233445566778899001122';

  /// One stored certificate, as `listCertificates` returns it.
  static Map<Object?, Object?> stored({
    String id = caFingerprint,
    String alias = 'MikroTik CA',
    String? subjectCn = 'l2tp-sstp-client Test CA',
    bool isCa = true,
    int notBefore = 1770000000000,
    int notAfter = 1900000000000,
    int usageCount = 0,
    int importedAt = 1780000000000,
  }) {
    return <Object?, Object?>{
      TrustContract.fieldId: id,
      TrustContract.fieldAlias: alias,
      TrustContract.fieldSubjectCn: subjectCn,
      TrustContract.fieldSubjectDn: 'CN=$subjectCn,O=Testing',
      TrustContract.fieldIssuerDn: 'CN=$subjectCn,O=Testing',
      TrustContract.fieldSerialNumber: '2a',
      TrustContract.fieldNotBefore: notBefore,
      TrustContract.fieldNotAfter: notAfter,
      TrustContract.fieldSha256: id,
      TrustContract.fieldSha1: 'ff00',
      TrustContract.fieldIsCa: isCa,
      TrustContract.fieldKeyUsage: 'keyCertSign, cRLSign',
      TrustContract.fieldSubjectAltNames: <Object?>['DNS:vpn.example.com'],
      TrustContract.fieldPublicKeyBits: 2048,
      TrustContract.fieldSignatureAlgorithm: 'SHA256withRSA',
      TrustContract.fieldImportedAt: importedAt,
      TrustContract.fieldUsageCount: usageCount,
    };
  }

  /// One candidate offered for import, as the three import paths return it.
  static Map<Object?, Object?> candidate({
    String id = leafFingerprint,
    String pem = '-----BEGIN CERTIFICATE-----\nMII\n-----END CERTIFICATE-----',
    int? chainPosition,
    List<Object?> warnings = const <Object?>[],
  }) {
    return <Object?, Object?>{
      ...stored(id: id, subjectCn: 'vpn.example.com', isCa: false)
        ..remove(TrustContract.fieldAlias)
        ..remove(TrustContract.fieldImportedAt)
        ..remove(TrustContract.fieldUsageCount),
      TrustContract.fieldPem: pem,
      TrustContract.fieldChainPosition: chainPosition,
      TrustContract.fieldWarnings: warnings,
    };
  }

  static Map<Object?, Object?> warning(String key, [String? detail]) {
    return <Object?, Object?>{
      TrustContract.fieldWarningKey: key,
      TrustContract.fieldWarningDetail: detail,
    };
  }
}
