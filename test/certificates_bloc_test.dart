import 'package:flutter_test/flutter_test.dart';

import 'package:tunnel_forge/features/trust/data/trust_contract.dart';
import 'package:tunnel_forge/features/trust/domain/trust_models.dart';
import 'package:tunnel_forge/features/trust/presentation/bloc/certificates_bloc.dart';

import 'support/certificate_fixtures.dart';
import 'support/fake_certificates_repository.dart';

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
