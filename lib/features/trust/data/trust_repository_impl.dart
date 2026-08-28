import 'package:tunnel_forge/features/trust/data/trust_bridge.dart';
import 'package:tunnel_forge/features/trust/domain/trust_models.dart';
import 'package:tunnel_forge/features/trust/domain/trust_repository.dart';

/// [CertificatesRepository] over the host's certificate store.
class CertificatesRepositoryImpl implements CertificatesRepository {
  CertificatesRepositoryImpl({TrustBridge? bridge})
    : _bridge = bridge ?? TrustBridge();

  final TrustBridge _bridge;

  @override
  Future<bool> delete(String id) => _bridge.deleteCertificate(id);

  @override
  Future<String?> exportPem(String id) => _bridge.exportCertificate(id);

  @override
  Future<List<CertificateCandidate>> fetchChain({
    required String host,
    required int port,
  }) => _bridge.fetchServerChain(host: host, port: port);

  @override
  Future<List<ServerCertificate>> import(
    List<CertificateImportRequest> requests,
  ) => _bridge.importCertificates(requests);

  @override
  Future<List<ServerCertificate>> list() => _bridge.listCertificates();

  @override
  Future<List<CertificateCandidate>> parsePem(String text) =>
      _bridge.parsePemText(text);

  @override
  Future<List<CertificateCandidate>?> pickFile() =>
      _bridge.pickCertificateFile();

  @override
  Future<List<TrustPolicy>> policies() => _bridge.listTrustPolicies();

  @override
  Future<bool> rename(String id, String alias) =>
      _bridge.renameCertificate(id, alias);
}
