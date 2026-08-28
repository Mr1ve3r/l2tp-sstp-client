import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:tunnel_forge/features/trust/domain/trust_models.dart';
import 'package:tunnel_forge/features/trust/presentation/bloc/certificates_bloc.dart';
import 'package:tunnel_forge/features/trust/presentation/pages/certificates_page.dart';
import 'package:tunnel_forge/features/trust/presentation/widgets/certificate_import_sheet.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';

import 'certificates_bloc_test.dart' show FakeCertificatesRepository;
import 'support/certificate_fixtures.dart';

/// Closing a bloc is not awaited in these tests: the widget tree is still
/// subscribed to it, and the close future only completes once the done event
/// has been delivered, which needs a frame the test is no longer pumping.
/// Awaiting it here blocks forever.
///
/// Lets the bloc answer and any route animation finish.
///
/// `pumpAndSettle` is unusable on this screen: the progress indicator shown
/// while the store is being read schedules frames forever, so settling never
/// happens. A fixed number of frames is enough — a sheet animates in well
/// inside half a second.
Future<void> settle(WidgetTester tester) async {
  for (var frame = 0; frame < 12; frame++) {
    await tester.pump(const Duration(milliseconds: 50));
  }
}

void main() {
  /// 2026-06-01, inside the fixtures' validity window.
  final now = DateTime.utc(2026, 6, 1);

  Widget host(CertificatesBloc bloc) {
    return MaterialApp(
      localizationsDelegates: const [AppLocalizations.delegate],
      supportedLocales: AppLocalizations.supportedLocales,
      home: BlocProvider<CertificatesBloc>.value(
        value: bloc,
        child: CertificatesPage(now: now),
      ),
    );
  }

  testWidgets('an empty store explains what the screen is for', (tester) async {
    final bloc = CertificatesBloc(FakeCertificatesRepository())
      ..add(const CertificatesStarted());

    await tester.pumpWidget(host(bloc));
    await settle(tester);

    final l10n = AppText.current;
    expect(find.text(l10n.noCertificatesStored), findsOneWidget);
    expect(find.text(l10n.addCertificate), findsOneWidget);
    unawaited(bloc.close());
  });

  testWidgets('a stored certificate shows its name, expiry and usage', (
    tester,
  ) async {
    final repository = FakeCertificatesRepository()
      ..stored.add(
        ServerCertificate.fromMap(
          CertificateFixtures.stored(
            alias: 'MikroTik CA',
            usageCount: 2,
            notAfter: DateTime.utc(2026, 6, 20).millisecondsSinceEpoch,
          ),
        ),
      );
    final bloc = CertificatesBloc(repository)..add(const CertificatesStarted());

    await tester.pumpWidget(host(bloc));
    await settle(tester);

    final l10n = AppText.current;
    expect(find.text('MikroTik CA'), findsOneWidget);
    expect(find.text(l10n.certificateExpiresInDays(19)), findsOneWidget);
    expect(find.text(l10n.certificateUsedByProfiles(2)), findsOneWidget);
    unawaited(bloc.close());
  });

  testWidgets('offered certificates are reviewed before anything is stored', (
    tester,
  ) async {
    final repository = FakeCertificatesRepository(
      pickResult: [
        CertificateCandidate.fromMap(
          CertificateFixtures.candidate(
            warnings: <Object?>[
              CertificateFixtures.warning(CertificateWarning.weakKey, '1024'),
            ],
          ),
        ),
      ],
    );
    final bloc = CertificatesBloc(repository)..add(const CertificatesStarted());
    await tester.pumpWidget(host(bloc));
    await settle(tester);

    bloc.add(const CertificatesFilePickRequested());
    await settle(tester);

    final l10n = AppText.current;
    expect(find.byType(CertificateImportSheet), findsOneWidget);
    expect(find.text(l10n.certificateWarningWeakKey(1024)), findsOneWidget);
    expect(repository.imported, isEmpty);

    await tester.tap(find.text(l10n.keepSelectedCertificates));
    await settle(tester);

    expect(repository.imported, hasLength(1));
    unawaited(bloc.close());
  });

  testWidgets('a downloaded chain warns that the fingerprint must be checked', (
    tester,
  ) async {
    final repository = FakeCertificatesRepository(
      pickResult: [
        CertificateCandidate.fromMap(
          CertificateFixtures.candidate(chainPosition: 0),
        ),
      ],
    );
    final bloc = CertificatesBloc(repository)..add(const CertificatesStarted());
    await tester.pumpWidget(host(bloc));
    await settle(tester);

    bloc.add(
      const CertificatesServerChainRequested(
        host: 'vpn.example.com',
        port: 443,
      ),
    );
    await settle(tester);

    expect(find.text(AppText.current.importFromServerWarning), findsOneWidget);
    unawaited(bloc.close());
  });

  // Both dialogs used to create their controllers in the page and dispose them
  // on the line after `Navigator.pop`, while the route was still animating out
  // with its field mounted. That threw "A TextEditingController was used after
  // being disposed" and then tripped an assertion inside the framework
  // (docs/MANUAL_TEST_PHASE5.md, finding F1). Driving each dialog end to end is
  // what catches it.
  testWidgets('pasting PEM text reaches the review sheet', (tester) async {
    final repository = FakeCertificatesRepository(
      pickResult: [
        CertificateCandidate.fromMap(CertificateFixtures.candidate()),
      ],
    );
    final bloc = CertificatesBloc(repository)..add(const CertificatesStarted());
    await tester.pumpWidget(host(bloc));
    await settle(tester);

    final l10n = AppText.current;
    await tester.tap(find.text(l10n.addCertificate));
    await settle(tester);
    await tester.tap(find.text(l10n.importFromText));
    await settle(tester);
    await tester.enterText(find.byType(TextField), 'pasted-pem');
    await settle(tester);
    await tester.tap(find.text(l10n.continueLabel));
    await settle(tester);

    expect(find.byType(CertificateImportSheet), findsOneWidget);
    unawaited(bloc.close());
  });

  testWidgets('a server address reaches the review sheet', (tester) async {
    final repository = FakeCertificatesRepository(
      pickResult: [
        CertificateCandidate.fromMap(
          CertificateFixtures.candidate(chainPosition: 0),
        ),
      ],
    );
    final bloc = CertificatesBloc(repository)..add(const CertificatesStarted());
    await tester.pumpWidget(host(bloc));
    await settle(tester);

    final l10n = AppText.current;
    await tester.tap(find.text(l10n.addCertificate));
    await settle(tester);
    await tester.tap(find.text(l10n.importFromServer));
    await settle(tester);
    await tester.enterText(find.byType(TextField).first, 'vpn.example.com');
    await settle(tester);
    await tester.tap(find.text(l10n.continueLabel));
    await settle(tester);

    expect(find.byType(CertificateImportSheet), findsOneWidget);
    unawaited(bloc.close());
  });

  testWidgets('a server dialog with no host stays open', (tester) async {
    final bloc = CertificatesBloc(FakeCertificatesRepository())
      ..add(const CertificatesStarted());
    await tester.pumpWidget(host(bloc));
    await settle(tester);

    final l10n = AppText.current;
    await tester.tap(find.text(l10n.addCertificate));
    await settle(tester);
    await tester.tap(find.text(l10n.importFromServer));
    await settle(tester);
    await tester.tap(find.text(l10n.continueLabel));
    await settle(tester);

    expect(find.text(l10n.certificateHost), findsOneWidget);
    unawaited(bloc.close());
  });
}
