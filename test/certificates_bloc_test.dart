import 'package:flutter_test/flutter_test.dart';

import 'package:tunnel_forge/features/trust/data/trust_contract.dart';
import 'package:tunnel_forge/features/trust/domain/trust_models.dart';
import 'package:tunnel_forge/features/trust/domain/trust_repository.dart';
import 'package:tunnel_forge/features/trust/presentation/bloc/certificates_bloc.dart';

import 'support/certificate_fixtures.dart';

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

void main() {
  final candidate = CertificateCandidate.fromMap(
    CertificateFixtures.candidate(),
  );

  test('starting loads the store and the policies this build offers', () async {
    final repository = FakeCertificatesRepository()
      ..stored.add(ServerCertificate.fromMap(CertificateFixtures.stored()));
    final bloc = CertificatesBloc(repository);

    bloc.add(const CertificatesStarted());
    await bloc.stream.firstWhere((state) => !state.loading);

    expect(bloc.state.certificates, hasLength(1));
    expect(bloc.state.policies, isNot(contains(TrustPolicy.insecure)));
    await bloc.close();
  });

  test('an import path offers what it found without storing it', () async {
    final repository = FakeCertificatesRepository(pickResult: [candidate]);
    final bloc = CertificatesBloc(repository);

    bloc.add(const CertificatesFilePickRequested());
    await bloc.stream.firstWhere((state) => state.candidates.isNotEmpty);

    expect(bloc.state.candidates, [candidate]);
    expect(repository.imported, isEmpty);
    await bloc.close();
  });

  test('backing out of the picker leaves the screen as it was', () async {
    final repository = FakeCertificatesRepository();
    final bloc = CertificatesBloc(repository);

    bloc.add(const CertificatesFilePickRequested());
    await bloc.stream.firstWhere((state) => !state.busy);

    expect(bloc.state.candidates, isEmpty);
    expect(bloc.state.failure, isNull);
    await bloc.close();
  });

  test('confirming an import stores it and reloads the list', () async {
    final repository = FakeCertificatesRepository(pickResult: [candidate]);
    final bloc = CertificatesBloc(repository);

    bloc.add(
      CertificatesImportConfirmed([
        CertificateImportRequest(pem: candidate.pem, alias: 'MikroTik'),
      ]),
    );
    await bloc.stream.firstWhere((state) => state.importedCount != null);

    expect(repository.imported.single.alias, 'MikroTik');
    expect(bloc.state.certificates, hasLength(1));
    expect(bloc.state.candidates, isEmpty);
    await bloc.close();
  });

  test('keeping nothing from the sheet stores nothing', () async {
    final repository = FakeCertificatesRepository(pickResult: [candidate]);
    final bloc = CertificatesBloc(repository);

    bloc.add(const CertificatesImportConfirmed([]));
    await bloc.stream.first;

    expect(repository.imported, isEmpty);
    await bloc.close();
  });

  test('deleting removes the certificate and refreshes the list', () async {
    final repository = FakeCertificatesRepository()
      ..stored.add(ServerCertificate.fromMap(CertificateFixtures.stored()));
    final bloc = CertificatesBloc(repository);

    bloc.add(
      const CertificatesDeleteRequested(CertificateFixtures.caFingerprint),
    );
    await bloc.stream.firstWhere((state) => !state.busy);

    expect(repository.deleted, [CertificateFixtures.caFingerprint]);
    expect(bloc.state.certificates, isEmpty);
    await bloc.close();
  });

  test('a failure is reported with the host code and then cleared', () async {
    final bloc = CertificatesBloc(
      FakeCertificatesRepository(
        failure: const TrustFailure(
          TrustContract.errorParseFailed,
          'not a certificate',
        ),
      ),
    );

    bloc.add(const CertificatesPemTextSubmitted('nonsense'));
    await bloc.stream.firstWhere((state) => state.failure != null);
    expect(bloc.state.failure?.code, TrustContract.errorParseFailed);

    bloc.add(const CertificatesNoticeCleared());
    await bloc.stream.firstWhere((state) => state.failure == null);
    await bloc.close();
  });

  test('exporting hands back the PEM once', () async {
    final bloc = CertificatesBloc(FakeCertificatesRepository());

    bloc.add(
      const CertificatesExportRequested(CertificateFixtures.caFingerprint),
    );
    await bloc.stream.firstWhere((state) => state.exportedPem != null);
    expect(
      bloc.state.exportedPem,
      'pem-of-${CertificateFixtures.caFingerprint}',
    );

    bloc.add(const CertificatesNoticeCleared());
    await bloc.stream.firstWhere((state) => state.exportedPem == null);
    await bloc.close();
  });
}
