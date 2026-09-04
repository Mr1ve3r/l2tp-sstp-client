/// Host/channel contract for the server certificate store (SPEC phase 5).
///
/// Mirrors `TrustContract.kt`. The two are kept in step by hand, and the
/// Kotlin side has a test over the map keys for exactly that reason.
abstract final class TrustContract {
  static const String channel = 'io.github.evokelektrique.tunnelforge/trust';

  /// Flutter -> host: every stored certificate, newest import first.
  static const String listCertificates = 'listCertificates';

  /// Flutter -> host: open the document picker and read what was chosen.
  static const String pickCertificateFile = 'pickCertificateFile';

  /// Flutter -> host: read certificates out of pasted text.
  static const String parsePemText = 'parsePemText';

  /// Flutter -> host: download the chain a server presents.
  static const String fetchServerChain = 'fetchServerChain';

  /// Flutter -> host: store the candidates the user accepted.
  static const String importCertificates = 'importCertificates';

  static const String deleteCertificate = 'deleteCertificate';
  static const String renameCertificate = 'renameCertificate';

  /// Flutter -> host: the stored PEM, for sharing or saving elsewhere.
  static const String exportCertificate = 'exportCertificate';

  /// Flutter -> host: which trust policies this build offers.
  static const String listTrustPolicies = 'listTrustPolicies';

  static const String argId = 'id';
  static const String argAlias = 'alias';
  static const String argText = 'text';
  static const String argHost = 'host';
  static const String argPort = 'port';
  static const String argCertificates = 'certificates';
  static const String argPem = 'pem';

  static const String fieldId = 'id';
  static const String fieldAlias = 'alias';
  static const String fieldSubjectCn = 'subjectCn';
  static const String fieldSubjectDn = 'subjectDn';
  static const String fieldIssuerDn = 'issuerDn';
  static const String fieldSerialNumber = 'serialNumber';
  static const String fieldNotBefore = 'notBefore';
  static const String fieldNotAfter = 'notAfter';
  static const String fieldSha256 = 'sha256Fingerprint';
  static const String fieldSha1 = 'sha1Fingerprint';
  static const String fieldIsCa = 'isCa';
  static const String fieldKeyUsage = 'keyUsage';
  static const String fieldSubjectAltNames = 'subjectAltNames';
  static const String fieldPublicKeyBits = 'publicKeyBits';
  static const String fieldSignatureAlgorithm = 'signatureAlgorithm';
  static const String fieldImportedAt = 'importedAt';
  static const String fieldUsageCount = 'usageCount';
  static const String fieldWarnings = 'warnings';
  static const String fieldPem = 'pem';
  static const String fieldChainPosition = 'chainPosition';

  static const String fieldWarningKey = 'key';
  static const String fieldWarningDetail = 'detail';

  static const String errorBadArgs = 'bad_args';
  static const String errorParseFailed = 'certificate_parse_failed';
  static const String errorReadFailed = 'certificate_read_failed';
  static const String errorFetchFailed = 'certificate_fetch_failed';
  static const String errorStoreFailed = 'certificate_store_failed';
}
