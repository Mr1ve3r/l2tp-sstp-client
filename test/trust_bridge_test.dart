import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:tunnel_forge/features/trust/data/trust_bridge.dart';
import 'package:tunnel_forge/features/trust/data/trust_contract.dart';
import 'package:tunnel_forge/features/trust/domain/trust_models.dart';

import 'support/certificate_fixtures.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('TrustContract', () {
    test('channel name matches the host', () {
      expect(
        TrustContract.channel,
        'io.github.evokelektrique.tunnelforge/trust',
      );
    });
  });

  group('TrustBridge', () {
    late List<MethodCall> calls;
    late Map<String, Object?> answers;
    late TrustBridge bridge;

    setUp(() {
      calls = [];
      answers = <String, Object?>{};
      const channel = MethodChannel(TrustContract.channel);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call);
            final answer = answers[call.method];
            if (answer is PlatformException) throw answer;
            return answer;
          });
      bridge = TrustBridge(channel: channel);
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel(TrustContract.channel),
            null,
          );
    });

    test('stored certificates arrive with alias, usage and expiry', () async {
      answers[TrustContract.listCertificates] = <Object?>[
        CertificateFixtures.stored(usageCount: 3),
      ];

      final certificates = await bridge.listCertificates();

      final certificate = certificates.single;
      expect(certificate.id, CertificateFixtures.caFingerprint);
      expect(certificate.alias, 'MikroTik CA');
      expect(certificate.usageCount, 3);
      expect(certificate.fields.isCa, isTrue);
      expect(certificate.fields.subjectAltNames, ['DNS:vpn.example.com']);
    });

    test('a candidate carries its PEM, warnings and chain position', () async {
      answers[TrustContract.fetchServerChain] = <Object?>[
        CertificateFixtures.candidate(
          chainPosition: 0,
          warnings: <Object?>[
            CertificateFixtures.warning(CertificateWarning.expiringSoon, '12'),
          ],
        ),
      ];

      final candidates = await bridge.fetchServerChain(
        host: 'vpn.example.com',
        port: 443,
      );

      final candidate = candidates.single;
      expect(candidate.chainPosition, 0);
      expect(candidate.pem, contains('BEGIN CERTIFICATE'));
      expect(candidate.warnings.single.key, CertificateWarning.expiringSoon);
      expect(candidate.warnings.single.detail, '12');
      expect(
        calls.single.arguments,
        containsPair(TrustContract.argHost, 'vpn.example.com'),
      );
    });

    test('backing out of the document picker is not a failure', () async {
      answers[TrustContract.pickCertificateFile] = null;

      expect(await bridge.pickCertificateFile(), isNull);
    });

    test('an import sends the PEM and the name the user chose', () async {
      answers[TrustContract.importCertificates] = <Object?>[
        CertificateFixtures.stored(),
      ];

      await bridge.importCertificates([
        const CertificateImportRequest(pem: 'pem-text', alias: 'MikroTik'),
      ]);

      final arguments = calls.single.arguments as Map<Object?, Object?>;
      final sent =
          (arguments[TrustContract.argCertificates] as List<Object?>).single
              as Map<Object?, Object?>;
      expect(sent[TrustContract.argPem], 'pem-text');
      expect(sent[TrustContract.argAlias], 'MikroTik');
    });

    test('a host failure keeps its code, so the UI can phrase it', () async {
      answers[TrustContract.parsePemText] = PlatformException(
        code: TrustContract.errorParseFailed,
        message: 'No BEGIN CERTIFICATE block',
      );

      await expectLater(
        bridge.parsePemText('not a certificate'),
        throwsA(
          isA<TrustFailure>().having(
            (failure) => failure.code,
            'code',
            TrustContract.errorParseFailed,
          ),
        ),
      );
    });

    test('policies the host does not offer are dropped', () async {
      // A release build answers without INSECURE, and an unknown name from a
      // newer host must not break the screen.
      answers[TrustContract.listTrustPolicies] = <Object?>[
        'SYSTEM',
        'PIN_LEAF',
        'SOMETHING_NEW',
      ];

      expect(await bridge.listTrustPolicies(), [
        TrustPolicy.system,
        TrustPolicy.pinLeaf,
      ]);
    });
  });

  group('CertificateFields', () {
    test('a fingerprint is shown the way a router prints it', () {
      expect(CertificateFields.formatFingerprint('aa11bb22'), 'AA:11:BB:22');
    });

    test('expiry is judged against the given moment', () {
      final fields = CertificateFields.fromMap(
        CertificateFixtures.stored(
          notBefore: DateTime.utc(2026, 1, 1).millisecondsSinceEpoch,
          notAfter: DateTime.utc(2026, 6, 1).millisecondsSinceEpoch,
        ),
      );

      expect(
        fields.expiryAt(DateTime.utc(2025, 12, 1)),
        CertificateExpiry.notYetValid,
      );
      expect(
        fields.expiryAt(DateTime.utc(2026, 3, 1)),
        CertificateExpiry.valid,
      );
      expect(
        fields.expiryAt(DateTime.utc(2026, 5, 20)),
        CertificateExpiry.expiringSoon,
      );
      expect(
        fields.expiryAt(DateTime.utc(2026, 7, 1)),
        CertificateExpiry.expired,
      );
    });
  });
}
