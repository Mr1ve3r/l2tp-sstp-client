import 'package:tunnel_forge/features/trust/domain/trust_models.dart';

/// The certificate store as the UI needs it (SPEC phase 5).
///
/// An interface rather than the bridge itself so the certificate screen can be
/// tested without a method channel behind it.
abstract class CertificatesRepository {
  Future<List<ServerCertificate>> list();

  Future<List<TrustPolicy>> policies();

  /// Opens the document picker. `null` means the user backed out.
  Future<List<CertificateCandidate>?> pickFile();

  Future<List<CertificateCandidate>> parsePem(String text);

  Future<List<CertificateCandidate>> fetchChain({
    required String host,
    required int port,
  });

  Future<List<ServerCertificate>> import(
    List<CertificateImportRequest> requests,
  );

  Future<bool> delete(String id);

  Future<bool> rename(String id, String alias);

  Future<String?> exportPem(String id);
}
