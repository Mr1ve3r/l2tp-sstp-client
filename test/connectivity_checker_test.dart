// The checker against real sockets on the loopback interface (SPEC 7.2).
//
// A real listener rather than a fake: what is being tested is that a TCP
// handshake is what gets timed, and a fake socket would only prove that the
// code calls the fake.
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:tunnel_forge/core/network/connectivity_checker.dart';

void main() {
  late HttpConnectivityChecker checker;

  setUp(() => checker = HttpConnectivityChecker());

  group('a bare address', () {
    test('is reachable when something is listening', () async {
      final server = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
      server.listen((socket) => socket.destroy());
      addTearDown(server.close);

      final result = await checker.ping(
        ConnectivityPingRequest.directWithTimeout(
          url: '127.0.0.1:${server.port}',
          timeoutMs: 2000,
        ),
      );

      expect(result.reachable, isTrue);
      expect(result.latencyMs, isNotNull);
      // A handshake has no status to report, and inventing one would put it in
      // the log as though the host had said it.
      expect(result.statusCode, isNull);
    });

    test('is unreachable when nothing is', () async {
      // Bound and closed again, so the port is one nothing can be listening on
      // rather than one picked out of the air.
      final server = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
      final port = server.port;
      await server.close();

      final result = await checker.ping(
        ConnectivityPingRequest.directWithTimeout(
          url: '127.0.0.1:$port',
          timeoutMs: 2000,
        ),
      );

      expect(result.reachable, isFalse);
      expect(result.error, isNotNull);
    });

    test(
      'an address that names nothing usable fails without dialling',
      () async {
        final result = await checker.ping(
          const ConnectivityPingRequest.direct('ftp://example.org'),
        );

        expect(result.reachable, isFalse);
        expect(result.error, isNotNull);
      },
    );
  });

  /// Proxy-only mode has no route of its own to dial down, so a bare address
  /// is asked for through the tunnel's HTTP proxy with a CONNECT.
  group('a bare address in proxy-only mode', () {
    test('is reachable when the proxy accepts the CONNECT', () async {
      final proxy = await _fakeProxy('HTTP/1.1 200 Connection established');
      addTearDown(proxy.close);

      final result = await checker.ping(
        ConnectivityPingRequest.localHttpProxy(
          url: '10.0.0.1:53',
          timeoutMs: 2000,
          proxyPort: proxy.port,
        ),
      );

      expect(result.reachable, isTrue);
      expect(result.statusCode, 200);
      expect(proxy.firstLine, 'CONNECT 10.0.0.1:53 HTTP/1.1');
    });

    test('is unreachable when the proxy refuses it', () async {
      final proxy = await _fakeProxy('HTTP/1.1 502 Bad Gateway');
      addTearDown(proxy.close);

      final result = await checker.ping(
        ConnectivityPingRequest.localHttpProxy(
          url: '10.0.0.1:53',
          timeoutMs: 2000,
          proxyPort: proxy.port,
        ),
      );

      expect(result.reachable, isFalse);
      expect(result.statusCode, 502);
    });
  });
}

/// A listener that answers one CONNECT with [status] and records what it was
/// asked for.
Future<_FakeProxy> _fakeProxy(String status) async {
  final server = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
  final proxy = _FakeProxy(server);
  server.listen((socket) {
    socket.listen((chunk) {
      proxy.request.write(String.fromCharCodes(chunk));
      socket.write('$status\r\n\r\n');
      socket.flush().then((_) => socket.destroy());
    });
  });
  return proxy;
}

class _FakeProxy {
  _FakeProxy(this._server);

  final ServerSocket _server;
  final StringBuffer request = StringBuffer();

  int get port => _server.port;

  String get firstLine => request.toString().split('\r\n').first;

  Future<void> close() => _server.close();
}
