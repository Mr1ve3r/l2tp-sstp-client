import 'package:tunnel_forge/features/trust/domain/trust_models.dart';
import 'package:tunnel_forge/features/trust/domain/trust_repository.dart';

import 'certificate_fixtures.dart';

/// A store that answers from memory, so the bloc can be driven without a host.
class FakeCertificatesRepository implements CertificatesRepository {
  FakeCertificatesRepository({this.pickResult, this.failure});

  final List<CertificateCandidate>? pickResult;
  final TrustFailure? failure;

  final List<ServerCertificate> stored = <ServerCertificate>[];
  final List<CertificateImportRequest> imported = <CertificateImportRequest>[];
  final List<String> deleted = <String>[];

  @override
  Future<bool> delete(String id) async {
    if (failure != null) throw failure!;
    deleted.add(id);
    stored.removeWhere((certificate) => certificate.id == id);
    return true;
  }

  @override
  Future<String?> exportPem(String id) async {
    if (failure != null) throw failure!;
    return 'pem-of-$id';
  }

  @override
  Future<List<CertificateCandidate>> fetchChain({
    required String host,
    required int port,
  }) async {
    if (failure != null) throw failure!;
    return pickResult ?? const <CertificateCandidate>[];
  }

  @override
  Future<List<ServerCertificate>> import(
    List<CertificateImportRequest> requests,
  ) async {
    if (failure != null) throw failure!;
    imported.addAll(requests);
    stored.add(ServerCertificate.fromMap(CertificateFixtures.stored()));
    return stored;
  }

  @override
  Future<List<ServerCertificate>> list() async {
    if (failure != null) throw failure!;
    return List<ServerCertificate>.from(stored);
  }

  @override
  Future<List<CertificateCandidate>> parsePem(String text) async {
    if (failure != null) throw failure!;
    return pickResult ?? const <CertificateCandidate>[];
  }

  @override
  Future<List<CertificateCandidate>?> pickFile() async {
    if (failure != null) throw failure!;
    return pickResult;
  }

  @override
  Future<List<TrustPolicy>> policies() async {
    if (failure != null) throw failure!;
    return const [TrustPolicy.system, TrustPolicy.pinLeaf];
  }

  @override
  Future<bool> rename(String id, String alias) async {
    if (failure != null) throw failure!;
    return true;
  }
}
