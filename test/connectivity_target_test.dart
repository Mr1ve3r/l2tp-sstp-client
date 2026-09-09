// What a connectivity check can be pointed at (SPEC 7.2).
import 'package:flutter_test/flutter_test.dart';
import 'package:tunnel_forge/core/network/connectivity_target.dart';

void main() {
  group('ConnectivityCheckTarget.tryParse', () {
    test('an http(s) URL is fetched', () {
      final target = ConnectivityCheckTarget.tryParse(
        'https://www.gstatic.com/generate_204',
      );

      expect(target, isA<HttpCheckTarget>());
      expect((target as HttpCheckTarget).uri.host, 'www.gstatic.com');
    });

    /// The case this exists for: a gateway or a resolver has no HTTP to fetch,
    /// and until now could not be checked at all.
    test('a bare address is dialled on the default port', () {
      final target = ConnectivityCheckTarget.tryParse('10.0.0.1');

      expect(target, isA<TcpCheckTarget>());
      expect((target as TcpCheckTarget).host, '10.0.0.1');
      expect(target.port, ConnectivityCheckTarget.defaultPort);
    });

    test('an address can name its own port', () {
      final target =
          ConnectivityCheckTarget.tryParse('8.8.8.8:53') as TcpCheckTarget;

      expect(target.host, '8.8.8.8');
      expect(target.port, 53);
      expect(target.authority, '8.8.8.8:53');
    });

    test('a hostname is an address too', () {
      final target =
          ConnectivityCheckTarget.tryParse('probe.acme.internal')
              as TcpCheckTarget;

      expect(target.host, 'probe.acme.internal');
      expect(target.port, ConnectivityCheckTarget.defaultPort);
    });

    /// `Uri.parse` reads `10.0.0.1:53` as the scheme `10.0.0.1`, so the shape
    /// of a scheme has to be recognised before anything is parsed.
    test('a port is not mistaken for a scheme', () {
      expect(
        ConnectivityCheckTarget.tryParse('10.0.0.1:53'),
        isA<TcpCheckTarget>(),
      );
    });

    test('an IPv6 address keeps its colons and brackets its authority', () {
      final bare =
          ConnectivityCheckTarget.tryParse('2001:db8::1') as TcpCheckTarget;
      expect(bare.host, '2001:db8::1');
      expect(bare.port, ConnectivityCheckTarget.defaultPort);
      expect(bare.authority, '[2001:db8::1]:443');

      final ported =
          ConnectivityCheckTarget.tryParse('[2001:db8::1]:853')
              as TcpCheckTarget;
      expect(ported.host, '2001:db8::1');
      expect(ported.port, 853);
    });

    /// The case this was asked for: an SMB share that answers to its name.
    /// A scheme is a name for a port number, so nothing has to be remembered.
    test('a service scheme is dialled on the port it stands for', () {
      final smb =
          ConnectivityCheckTarget.tryParse('smb://disk.corp.example')
              as TcpCheckTarget;
      expect(smb.host, 'disk.corp.example');
      expect(smb.port, 445);

      expect(
        (ConnectivityCheckTarget.tryParse('rdp://ts.corp.example')
                as TcpCheckTarget)
            .port,
        3389,
      );
      expect(
        (ConnectivityCheckTarget.tryParse('ssh://git.corp.example')
                as TcpCheckTarget)
            .port,
        22,
      );
      expect(
        (ConnectivityCheckTarget.tryParse('dns://10.0.0.1') as TcpCheckTarget)
            .port,
        53,
      );
    });

    test('an explicit port wins over the scheme default', () {
      final target =
          ConnectivityCheckTarget.tryParse('smb://disk.corp.example:4445')
              as TcpCheckTarget;

      expect(target.host, 'disk.corp.example');
      expect(target.port, 4445);
    });

    test('tcp:// is the escape hatch for anything unlisted', () {
      final target =
          ConnectivityCheckTarget.tryParse('tcp://build.corp.example:8443')
              as TcpCheckTarget;

      expect(target.host, 'build.corp.example');
      expect(target.port, 8443);
    });

    test('a scheme is read whatever its case, with or without slashes', () {
      for (final text in <String>[
        'SMB://disk.corp.example',
        'smb:disk.corp.example',
        'smb:/disk.corp.example',
      ]) {
        final target = ConnectivityCheckTarget.tryParse(text);
        expect(target, isA<TcpCheckTarget>(), reason: text);
        expect((target as TcpCheckTarget).port, 445, reason: text);
      }
    });

    /// Reaching the server is all that can be measured, so a share name is not
    /// quietly accepted and then ignored.
    test('a path after the host is refused', () {
      expect(
        ConnectivityCheckTarget.tryParse('smb://disk.example/share'),
        isNull,
      );
    });

    test('a scheme this application cannot speak is refused', () {
      expect(ConnectivityCheckTarget.tryParse('ftp://example.org'), isNull);
      expect(ConnectivityCheckTarget.tryParse('file:///etc/hosts'), isNull);
    });

    test('nonsense is refused rather than guessed at', () {
      expect(ConnectivityCheckTarget.tryParse(''), isNull);
      expect(ConnectivityCheckTarget.tryParse('   '), isNull);
      expect(ConnectivityCheckTarget.tryParse('10.0.0.1:0'), isNull);
      expect(ConnectivityCheckTarget.tryParse('10.0.0.1:70000'), isNull);
      expect(ConnectivityCheckTarget.tryParse('10.0.0.1:http'), isNull);
      expect(ConnectivityCheckTarget.tryParse('10.0.0.1/probe'), isNull);
    });
  });
}
