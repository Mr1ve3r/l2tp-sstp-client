import 'package:flutter_test/flutter_test.dart';
import 'package:tunnel_forge/core/vpn_protocol.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_bundle.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_transfer.dart';
import 'package:tunnel_forge/features/trust/domain/trust_models.dart';

const _corporateCa = TransferredCertificate(
  id: 'ca-fingerprint',
  alias: 'Acme CA',
  pem: '-----BEGIN CERTIFICATE-----\nQUNNRQ==\n-----END CERTIFICATE-----',
);

Profile _profile(String name, {VpnProtocol protocol = VpnProtocol.l2tp}) {
  return Profile(
    id: 'local-$name',
    displayName: name,
    server: '$name.acme.example',
    user: 'alice',
    protocol: protocol,
    port: protocol == VpnProtocol.sstp ? 443 : 1701,
    dnsAutomatic: true,
    dns1Host: '',
    dns1Protocol: DnsProtocol.dnsOverUdp,
    dns2Host: '',
    dns2Protocol: DnsProtocol.dnsOverUdp,
    trustPolicy: protocol == VpnProtocol.sstp
        ? TrustPolicy.systemPlusCustom
        : TrustPolicy.system,
    trustedCertificateIds: protocol == VpnProtocol.sstp
        ? const ['ca-fingerprint']
        : const [],
  );
}

ProfileTransferEnvelope _entry(String name, {required VpnProtocol protocol}) {
  return ProfileTransferEnvelope(
    profile: _profile(name, protocol: protocol),
    password: 'alice-password',
    psk: 'corporate-psk',
    proxyPassword: 'proxy-password',
    certificates: protocol == VpnProtocol.sstp
        ? const [_corporateCa]
        : const [],
  );
}

ProfileBundle _bundle() {
  return ProfileBundle(
    name: 'Acme corporate VPN',
    createdAt: DateTime.utc(2026, 9, 7, 10),
    entries: [
      for (final name in ['ams', 'ber', 'cph'])
        _entry(name, protocol: VpnProtocol.l2tp),
      for (final name in ['dub', 'ein', 'fra'])
        _entry(name, protocol: VpnProtocol.sstp),
    ],
  );
}

void main() {
  group('ProfileBundle', () {
    test('round-trips six profiles across both protocols', () {
      final document = ProfileTransferDocument.parse(_bundle().toFileJson());

      expect(document, isA<ProfileSetDocument>());
      final decoded = (document as ProfileSetDocument).bundle;
      expect(decoded.name, 'Acme corporate VPN');
      expect(decoded.createdAt, DateTime.utc(2026, 9, 7, 10));
      expect(decoded.length, 6);
      expect(
        decoded.entries.where((e) => e.profile.protocol == VpnProtocol.l2tp),
        hasLength(3),
      );
      final sstp = decoded.entries
          .where((e) => e.profile.protocol == VpnProtocol.sstp)
          .toList();
      expect(sstp, hasLength(3));
      expect(sstp.first.profile.trustPolicy, TrustPolicy.systemPlusCustom);
      expect(decoded.entries.first.profile.server, 'ams.acme.example');
    });

    /// The point of the whole feature: a set is something an administrator can
    /// hand to six people without handing out anybody's login.
    test('a set carries the shared secrets and neither vpn credential', () {
      final text = _bundle().toFileJson();

      expect(text.contains('alice'), isFalse);
      expect(text.contains('alice-password'), isFalse);
      expect(text.contains('corporate-psk'), isTrue);

      final decoded =
          (ProfileTransferDocument.parse(text) as ProfileSetDocument).bundle;
      for (final entry in decoded.entries) {
        expect(entry.psk, 'corporate-psk');
        expect(entry.proxyPassword, 'proxy-password');
        expect(entry.password, isEmpty);
        expect(entry.profile.user, isEmpty);
      }
    });

    test(
      'the shared certificate is written once and put back on every entry',
      () {
        final bundle = _bundle();
        expect(bundle.certificatePool, hasLength(1));

        final text = bundle.toFileJson();
        expect('QUNNRQ=='.allMatches(text), hasLength(1));

        final decoded =
            (ProfileTransferDocument.parse(text) as ProfileSetDocument).bundle;
        final sstp = decoded.entries.where(
          (e) => e.profile.protocol == VpnProtocol.sstp,
        );
        expect(sstp, hasLength(3));
        for (final entry in sstp) {
          expect(entry.certificates, hasLength(1));
          expect(entry.certificates.single.alias, 'Acme CA');
        }
        for (final entry in decoded.entries.where(
          (e) => e.profile.protocol == VpnProtocol.l2tp,
        )) {
          expect(entry.certificates, isEmpty);
        }
      },
    );

    test('an entry keeps the envelope validation', () {
      expect(
        () => ProfileBundle.fromJsonMap({
          'v': ProfileBundle.version,
          'name': 'Broken',
          'entries': [
            {
              'profile': <String, Object?>{
                ..._profile('ams').toJson(),
                'server': '',
              },
            },
          ],
        }),
        throwsFormatException,
      );
    });

    test('refuses a set with no profiles in it', () {
      expect(
        () => ProfileBundle.fromJsonMap({
          'v': ProfileBundle.version,
          'name': 'Empty',
          'entries': <Object?>[],
        }),
        throwsFormatException,
      );
    });

    test('refuses an unsupported set version', () {
      expect(
        () => ProfileBundle.fromJsonMap({'v': 99, 'entries': <Object?>[]}),
        throwsFormatException,
      );
    });

    test('names the file after the set', () {
      expect(
        ProfileBundle.exportFileNameFor('Acme corporate VPN'),
        'acme-corporate-vpn.tfp',
      );
      expect(
        ProfileBundle.exportFileNameFor('  '),
        'tunnel-forge-profiles.tfp',
      );
    });
  });

  group('ProfileTransferDocument', () {
    test('reads a single profile file as one profile', () {
      final text = ProfileTransferEnvelope(
        profile: _profile('ams'),
        psk: 'corporate-psk',
      ).toFileJson(secrets: TransferSecrets.all);

      final document = ProfileTransferDocument.parse(text);

      expect(document, isA<SingleProfileDocument>());
      expect(
        (document as SingleProfileDocument).envelope.profile.server,
        'ams.acme.example',
      );
    });

    test('refuses a container that has not been opened', () {
      expect(
        () => ProfileTransferDocument.parse('VEZQQzExMTExMTE='),
        throwsFormatException,
      );
    });
  });
}
