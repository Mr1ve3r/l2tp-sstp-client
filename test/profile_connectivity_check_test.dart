// A profile's own connectivity check, and how it travels (SPEC 8.1, 8.1.4).
import 'package:flutter_test/flutter_test.dart';
import 'package:tunnel_forge/core/vpn_protocol.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_bundle.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_transfer.dart';

Profile _profile({String url = '', int timeoutMs = 0}) {
  return Profile(
    id: 'local',
    displayName: 'Amsterdam',
    server: 'ams.acme.example',
    user: 'alice',
    protocol: VpnProtocol.l2tp,
    port: 1701,
    dnsAutomatic: true,
    dns1Host: '',
    dns1Protocol: DnsProtocol.dnsOverUdp,
    dns2Host: '',
    dns2Protocol: DnsProtocol.dnsOverUdp,
    connectivityCheckUrl: url,
    connectivityCheckTimeoutMs: timeoutMs,
  );
}

const _global = ConnectivityCheckSettings(
  url: 'https://example.org/generate_204',
  timeoutMs: 4000,
);

void main() {
  group('Profile.effectiveConnectivityCheck', () {
    test('a profile that names nothing uses the global setting', () {
      expect(_profile().effectiveConnectivityCheck(_global), _global);
    });

    test('a profile that names both uses its own', () {
      final settings = _profile(
        url: 'http://probe.acme.internal/',
        timeoutMs: 1500,
      ).effectiveConnectivityCheck(_global);

      expect(settings.url, 'http://probe.acme.internal/');
      expect(settings.timeoutMs, 1500);
    });

    /// The two halves fall back separately: naming an endpoint is not a
    /// statement about how long to wait for it.
    test('the halves fall back on their own', () {
      final urlOnly = _profile(
        url: 'http://probe.acme.internal/',
      ).effectiveConnectivityCheck(_global);
      expect(urlOnly.url, 'http://probe.acme.internal/');
      expect(urlOnly.timeoutMs, 4000);

      final timeoutOnly = _profile(
        timeoutMs: 1500,
      ).effectiveConnectivityCheck(_global);
      expect(timeoutOnly.url, 'https://example.org/generate_204');
      expect(timeoutOnly.timeoutMs, 1500);
    });
  });

  /// The endpoint is what an organisation wants to hand out with the profiles,
  /// which only works if the export carries it.
  group('export', () {
    test('the check travels in an exported profile', () {
      final envelope = ProfileTransferEnvelope(
        profile: _profile(url: 'http://probe.acme.internal/', timeoutMs: 1500),
        psk: 'corporate-psk',
      );

      final decoded = ProfileTransferEnvelope.fromFileJson(
        envelope.toFileJson(),
      );

      expect(
        decoded.profile.connectivityCheckUrl,
        'http://probe.acme.internal/',
      );
      expect(decoded.profile.connectivityCheckTimeoutMs, 1500);
    });

    test('storing an imported profile keeps the check', () {
      final envelope = ProfileTransferEnvelope(
        profile: _profile(url: 'http://probe.acme.internal/', timeoutMs: 1500),
      );

      final stored = envelope.toProfile('new-id');

      expect(stored.connectivityCheckUrl, 'http://probe.acme.internal/');
      expect(stored.connectivityCheckTimeoutMs, 1500);
    });

    /// The check names a host on the sender's network. Inside an organisation
    /// that is the point; outside it, the recipient would get a badge that
    /// calls a working tunnel broken.
    test('the export can be told to leave the check behind', () {
      final envelope = ProfileTransferEnvelope(
        profile: _profile(url: 'http://probe.acme.internal/', timeoutMs: 1500),
        psk: 'corporate-psk',
      );

      final decoded = ProfileTransferEnvelope.fromFileJson(
        envelope.toFileJson(includeConnectivityCheck: false),
      );

      expect(decoded.profile.connectivityCheckUrl, isEmpty);
      expect(decoded.profile.connectivityCheckTimeoutMs, 0);
      // Everything else still travels.
      expect(decoded.profile.server, 'ams.acme.example');
    });

    test('a set carries the choice through to its entries', () {
      final bundle = ProfileBundle(
        name: 'Acme',
        entries: [
          ProfileTransferEnvelope(
            profile: _profile(
              url: 'http://probe.acme.internal/',
              timeoutMs: 1500,
            ),
          ),
        ],
      );

      final kept =
          (ProfileTransferDocument.parse(bundle.toFileJson())
                  as ProfileSetDocument)
              .bundle;
      expect(
        kept.entries.single.profile.connectivityCheckUrl,
        'http://probe.acme.internal/',
      );

      final dropped =
          (ProfileTransferDocument.parse(
                    bundle.toFileJson(includeConnectivityCheck: false),
                  )
                  as ProfileSetDocument)
              .bundle;
      expect(dropped.entries.single.profile.connectivityCheckUrl, isEmpty);
      expect(dropped.entries.single.profile.connectivityCheckTimeoutMs, 0);
    });

    test('a profile with no check of its own has nothing to offer', () {
      final envelope = ProfileTransferEnvelope(profile: _profile());

      expect(envelope.hasConnectivityCheck, isFalse);
      expect(
        ProfileTransferEnvelope(
          profile: _profile(url: 'http://probe.acme.internal/'),
        ).hasConnectivityCheck,
        isTrue,
      );
    });

    test('a profile written before the field existed reads as no override', () {
      final decoded = Profile.tryFromJson(<String, Object?>{
        'id': 'legacy',
        'displayName': 'Amsterdam',
        'server': 'ams.acme.example',
        'user': 'alice',
        'dnsAutomatic': true,
        'dns1Host': '',
        'dns1Protocol': 'dnsOverUdp',
        'dns2Host': '',
        'dns2Protocol': 'dnsOverUdp',
        'mtu': 1400,
      });

      expect(decoded!.connectivityCheckUrl, isEmpty);
      expect(decoded.connectivityCheckTimeoutMs, 0);
      expect(decoded.effectiveConnectivityCheck(_global), _global);
    });
  });
}
