import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:tunnel_forge/core/vpn_protocol.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/trust/domain/trust_models.dart';
import 'package:tunnel_forge/features/tunnel/data/vpn_client.dart';
import 'package:tunnel_forge/features/tunnel/data/vpn_contract.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('VpnClient.prepareVpn', () {
    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel(VpnContract.channel),
            null,
          );
    });

    test('returns false when platform returns null', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), (
            call,
          ) async {
            expect(call.method, VpnContract.prepareVpn);
            return null;
          });
      expect(await VpnClient().prepareVpn(), isFalse);
    });

    test('returns false when platform returns non-bool', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), (
            call,
          ) async {
            return 'yes';
          });
      expect(await VpnClient().prepareVpn(), isFalse);
    });
  });

  group('VpnClient.connect', () {
    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel(VpnContract.channel),
            null,
          );
    });

    test('throws PlatformException when platform reports error', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), (
            call,
          ) async {
            if (call.method == VpnContract.connect) {
              throw PlatformException(
                code: 'vpn_permission',
                message: 'not granted',
              );
            }
            return null;
          });
      expect(
        () => VpnClient().connect(server: 'x.example'),
        throwsA(
          isA<PlatformException>().having(
            (e) => e.code,
            'code',
            'vpn_permission',
          ),
        ),
      );
    });

    test('sends ordered dns servers in manual mode', () async {
      MethodCall? capturedCall;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), (
            call,
          ) async {
            capturedCall = call;
            return null;
          });

      await VpnClient().connect(
        server: 'x.example',
        dnsAutomatic: false,
        dnsServers: const [
          DnsServerConfig(host: '1.1.1.1', protocol: DnsProtocol.dnsOverUdp),
          DnsServerConfig(host: '8.8.8.8', protocol: DnsProtocol.dnsOverTcp),
          DnsServerConfig(host: '1.1.1.1', protocol: DnsProtocol.dnsOverUdp),
        ],
      );

      expect(capturedCall?.method, VpnContract.connect);
      final args = Map<Object?, Object?>.from(capturedCall?.arguments as Map);
      expect(args[VpnContract.argDnsAutomatic], isFalse);
      expect(args[VpnContract.argDnsServers], [
        {
          VpnContract.argDnsServerHost: '1.1.1.1',
          VpnContract.argDnsServerProtocol: DnsProtocol.dnsOverUdp.jsonValue,
        },
        {
          VpnContract.argDnsServerHost: '8.8.8.8',
          VpnContract.argDnsServerProtocol: DnsProtocol.dnsOverTcp.jsonValue,
        },
        {
          VpnContract.argDnsServerHost: '1.1.1.1',
          VpnContract.argDnsServerProtocol: DnsProtocol.dnsOverUdp.jsonValue,
        },
      ]);
    });

    test('sends automatic dns mode without explicit servers', () async {
      MethodCall? capturedCall;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), (
            call,
          ) async {
            capturedCall = call;
            return null;
          });

      await VpnClient().connect(server: 'x.example');

      final args = Map<Object?, Object?>.from(capturedCall?.arguments as Map);
      expect(args[VpnContract.argDnsAutomatic], isTrue);
      expect(args[VpnContract.argDnsServers], isEmpty);
    });

    test('an SSTP request names the protocol and carries its fields', () async {
      MethodCall? capturedCall;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), (
            call,
          ) async {
            capturedCall = call;
            return null;
          });

      await VpnClient().connect(
        server: 'x.example',
        protocol: VpnProtocol.sstp,
        sstp: const SstpConnectSettings(
          port: 4443,
          trustPolicy: TrustPolicy.customOnly,
          trustedCertificateIds: ['abc'],
          pinnedFingerprints: ['def'],
          expectedHostname: 'vpn.example',
          minTlsVersion: TlsVersion.tls13,
          pppAuthMethods: [PppAuthMethod.mschapv2],
          proxyHost: 'proxy.example',
          proxyPort: 3128,
          proxyUsername: 'u',
          proxyPassword: 'p',
        ),
      );

      final args = Map<Object?, Object?>.from(capturedCall?.arguments as Map);
      expect(args[VpnContract.argProtocol], VpnProtocol.sstp.wireValue);
      expect(args[VpnContract.argSstpPort], 4443);
      expect(
        args[VpnContract.argSstpTrustPolicy],
        TrustPolicy.customOnly.wireName,
      );
      expect(args[VpnContract.argSstpCertificateIds], ['abc']);
      expect(args[VpnContract.argSstpPinnedFingerprints], ['def']);
      expect(args[VpnContract.argSstpExpectedHostname], 'vpn.example');
      expect(args[VpnContract.argSstpMinTlsVersion], TlsVersion.tls13.wireName);
      expect(args[VpnContract.argSstpAuthMethods], [
        PppAuthMethod.mschapv2.wireName,
      ]);
      expect(args[VpnContract.argSstpProxyHost], 'proxy.example');
      expect(args[VpnContract.argSstpProxyPort], 3128);
      expect(args[VpnContract.argSstpProxyUsername], 'u');
      expect(args[VpnContract.argSstpProxyPassword], 'p');
    });

    test('an L2TP request carries no SSTP fields', () async {
      MethodCall? capturedCall;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel(VpnContract.channel), (
            call,
          ) async {
            capturedCall = call;
            return null;
          });

      await VpnClient().connect(server: 'x.example');

      final args = Map<Object?, Object?>.from(capturedCall?.arguments as Map);
      expect(args[VpnContract.argProtocol], VpnProtocol.l2tp.wireValue);
      expect(args.containsKey(VpnContract.argSstpTrustPolicy), isFalse);
      expect(args.containsKey(VpnContract.argSstpCertificateIds), isFalse);
    });

    test('a disabled proxy reaches the host as no proxy at all', () {
      const profile = Profile(
        id: 'p',
        displayName: 'p',
        server: 'x.example',
        user: 'u',
        protocol: VpnProtocol.sstp,
        proxyEnabled: false,
        proxyHost: 'proxy.example',
        proxyPort: 3128,
        proxyUsername: 'u',
      );

      final sstp = SstpConnectSettings.fromProfile(profile, proxyPassword: 'p');

      expect(sstp.proxyHost, isEmpty);
      expect(sstp.proxyUsername, isEmpty);
      expect(sstp.proxyPassword, isEmpty);
    });
  });
}
