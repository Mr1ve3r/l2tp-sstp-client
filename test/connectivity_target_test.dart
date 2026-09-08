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
