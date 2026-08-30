import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tunnel_forge/core/vpn_protocol.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/data/profile_bridge.dart';
import 'package:tunnel_forge/features/profiles/data/profile_store.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_transfer.dart';
import 'package:tunnel_forge/features/trust/domain/trust_models.dart';

const _profile = Profile(
  id: 'profile-1',
  displayName: 'Office',
  server: 'vpn.example.com',
  user: 'alice',
  dnsAutomatic: false,
  dns1Host: '1.1.1.1',
  dns1Protocol: DnsProtocol.dnsOverUdp,
  dns2Host: 'dns.example.com',
  dns2Protocol: DnsProtocol.dnsOverTls,
  mtu: 1400,
);

ProfileTransferEnvelope _envelope({
  Profile profile = _profile,
  List<TransferredCertificate> certificates = const <TransferredCertificate>[],
}) {
  return ProfileTransferEnvelope(
    profile: profile,
    password: 'pw',
    psk: 'psk',
    certificates: certificates,
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('ProfileTransferEnvelope', () {
    test('round-trips through .tfp json', () {
      final decoded = ProfileTransferEnvelope.fromFileJson(
        _envelope().toFileJson(includeSecrets: true),
      );

      expect(decoded.profile.displayName, 'Office');
      expect(decoded.profile.server, 'vpn.example.com');
      expect(decoded.password, 'pw');
      expect(decoded.psk, 'psk');
      expect(decoded.profile.dnsAutomatic, isFalse);
      expect(decoded.profile.dns1Host, '1.1.1.1');
      expect(decoded.profile.dns2Protocol, DnsProtocol.dnsOverTls);
      expect(decoded.profile.mtu, 1400);
    });

    /// SPEC 8.2, acceptance criterion 3.
    test('an export without secrets contains no secret in any form', () {
      final text = _envelope().toFileJson();

      expect(text.contains('pw'), isFalse);
      expect(text.contains('psk'), isFalse);
      final decoded = jsonDecode(text) as Map<String, Object?>;
      expect(decoded.containsKey('password'), isFalse);
      expect(decoded.containsKey('psk'), isFalse);
      expect(decoded.containsKey('proxyPassword'), isFalse);
      expect(ProfileTransferEnvelope.fromFileJson(text).hasSecrets, isFalse);
    });

    test('carries every SSTP field and the certificates it trusts', () {
      final profile = _profile.copyWith(
        protocol: VpnProtocol.sstp,
        port: 4443,
        trustPolicy: TrustPolicy.customOnly,
        expectedHostname: 'vpn.internal.lan',
        minTlsVersion: TlsVersion.tls13,
        pppAuthMethods: const [PppAuthMethod.mschapv2],
        proxyEnabled: true,
        proxyHost: 'proxy.example.org',
        proxyPort: 3128,
        proxyUsername: 'bob',
      );

      final decoded = ProfileTransferEnvelope.fromFileJson(
        _envelope(
          profile: profile,
          certificates: const [
            TransferredCertificate(
              id: 'aa11',
              alias: 'Work CA',
              pem:
                  '-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----',
            ),
          ],
        ).toFileJson(),
      );

      expect(decoded.profile.protocol, VpnProtocol.sstp);
      expect(decoded.profile.port, 4443);
      expect(decoded.profile.trustPolicy, TrustPolicy.customOnly);
      expect(decoded.profile.expectedHostname, 'vpn.internal.lan');
      expect(decoded.profile.minTlsVersion, TlsVersion.tls13);
      expect(decoded.profile.pppAuthMethods, [PppAuthMethod.mschapv2]);
      expect(decoded.profile.proxyHost, 'proxy.example.org');
      expect(decoded.certificates, hasLength(1));
      expect(decoded.certificates.single.alias, 'Work CA');
    });

    test('round-trips through tf uri payload, secrets included', () {
      final decoded = ProfileTransferEnvelope.fromTfUri(_envelope().toTfUri());

      expect(decoded.profile.server, 'vpn.example.com');
      expect(decoded.password, 'pw');
      expect(decoded.psk, 'psk');
    });

    test('rejects unsupported version', () {
      expect(
        () => ProfileTransferEnvelope.fromJsonMap({
          'v': 99,
          'profile': _profile.toJson(),
        }),
        throwsFormatException,
      );
    });

    /// A file exported by the build before phase 8 still opens.
    test('reads a version 3 file', () {
      final decoded = ProfileTransferEnvelope.fromJsonMap({
        'v': 3,
        'displayName': 'Office',
        'server': 'vpn.example.com',
        'user': 'alice',
        'password': 'pw',
        'psk': 'psk',
        'dnsAutomatic': false,
        'dns1Host': '1.1.1.1',
        'dns1Protocol': 'dnsOverUdp',
        'dns2Host': '',
        'dns2Protocol': 'dnsOverUdp',
        'mtu': 1400,
      });

      expect(decoded.profile.server, 'vpn.example.com');
      expect(decoded.profile.protocol, VpnProtocol.l2tp);
      expect(decoded.password, 'pw');
      expect(decoded.psk, 'psk');
    });

    test('preserves dns-over-https endpoint path on import', () {
      final decoded = ProfileTransferEnvelope.fromJsonMap({
        'v': ProfileTransferEnvelope.currentVersion,
        'profile': _profile
            .copyWith(
              dns1Host: 'wikimedia-dns.org/dns-query',
              dns1Protocol: DnsProtocol.dnsOverHttps,
              dns2Host: '',
              dns2Protocol: DnsProtocol.dnsOverUdp,
            )
            .toJson(),
      });

      expect(decoded.profile.dns1Host, 'wikimedia-dns.org/dns-query');
      expect(decoded.profile.dns1Protocol, DnsProtocol.dnsOverHttps);
    });

    test('refuses a profile with no server', () {
      expect(
        () => ProfileTransferEnvelope.fromJsonMap({
          'v': ProfileTransferEnvelope.currentVersion,
          'profile': <String, Object?>{..._profile.toJson(), 'server': ''},
        }),
        throwsFormatException,
      );
    });
  });

  group('ProfileStore imported profiles', () {
    late SharedPreferences prefs;
    late MemorySecretStore secrets;
    late ProfileStore store;

    setUp(() async {
      SharedPreferences.setMockInitialValues({});
      prefs = await SharedPreferences.getInstance();
      await prefs.clear();
      secrets = MemorySecretStore();
      store = ProfileStore(
        prefsOverride: prefs,
        secretsOverride: secrets,
        backendOverride: MemoryProfileBackend(),
      );
    });

    test('saveImportedProfile creates a new row and secrets', () async {
      const existing = Profile(
        id: 'existing',
        displayName: 'Office',
        server: 'vpn.example.com',
        user: 'alice',
        dnsAutomatic: false,
        dns1Host: '1.1.1.1',
        dns1Protocol: DnsProtocol.dnsOverUdp,
        dns2Host: '',
        dns2Protocol: DnsProtocol.dnsOverUdp,
      );
      await store.upsertProfile(existing, password: 'old', psk: 'old-psk');

      final imported = await store.saveImportedProfile(
        ProfileTransferEnvelope(
          profile: existing,
          password: 'new',
          psk: 'new-psk',
        ),
      );

      final profiles = await store.loadProfiles();
      expect(profiles, hasLength(2));
      expect(imported.id, isNot('existing'));
      final importedRow = await store.loadProfileWithSecrets(imported.id);
      expect(importedRow, isNotNull);
      expect(importedRow!.password, 'new');
      expect(importedRow.psk, 'new-psk');
    });
  });
}
