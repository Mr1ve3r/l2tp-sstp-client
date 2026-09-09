import 'dart:async';
import 'dart:io';

import 'package:equatable/equatable.dart';

import 'package:tunnel_forge/core/network/connectivity_target.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';

class ConnectivityPingResult {
  const ConnectivityPingResult({
    required this.reachable,
    this.latencyMs,
    this.statusCode,
    this.error,
  });

  final bool reachable;
  final int? latencyMs;
  final int? statusCode;
  final String? error;

  /// @param statusCode absent for a check that opened a socket rather than
  ///   fetching anything: there is no status in a TCP handshake, and reporting
  ///   a made-up one would put it in the log as though the host had said it.
  factory ConnectivityPingResult.success({
    required int latencyMs,
    int? statusCode,
  }) {
    return ConnectivityPingResult(
      reachable: true,
      latencyMs: latencyMs,
      statusCode: statusCode,
    );
  }

  factory ConnectivityPingResult.failure({int? statusCode, String? error}) {
    return ConnectivityPingResult(
      reachable: false,
      statusCode: statusCode,
      error: error,
    );
  }
}

enum ConnectivityPingRoute { direct, localHttpProxy }

class ConnectivityPingRequest extends Equatable {
  const ConnectivityPingRequest._({
    required this.url,
    required this.timeoutMs,
    required this.route,
    this.proxyHost,
    this.proxyPort,
  });

  const ConnectivityPingRequest.direct(String url)
    : this._(
        url: url,
        timeoutMs: ConnectivityCheckSettings.defaultTimeoutMs,
        route: ConnectivityPingRoute.direct,
      );

  const ConnectivityPingRequest.directWithTimeout({
    required String url,
    required int timeoutMs,
  }) : this._(
         url: url,
         timeoutMs: timeoutMs,
         route: ConnectivityPingRoute.direct,
       );

  const ConnectivityPingRequest.localHttpProxy({
    required String url,
    int timeoutMs = ConnectivityCheckSettings.defaultTimeoutMs,
    String proxyHost = _defaultProxyHost,
    required int proxyPort,
  }) : this._(
         url: url,
         timeoutMs: timeoutMs,
         route: ConnectivityPingRoute.localHttpProxy,
         proxyHost: proxyHost,
         proxyPort: proxyPort,
       );

  static const String _defaultProxyHost = '127.0.0.1';

  final String url;
  final int timeoutMs;
  final ConnectivityPingRoute route;
  final String? proxyHost;
  final int? proxyPort;

  @override
  List<Object?> get props => [url, timeoutMs, route, proxyHost, proxyPort];
}

abstract class ConnectivityChecker {
  Future<ConnectivityPingResult> ping(ConnectivityPingRequest request);
}

class HttpConnectivityChecker implements ConnectivityChecker {
  HttpConnectivityChecker({
    this.timeout = const Duration(milliseconds: 5000),
    HttpClient Function()? clientFactory,
  }) : _clientFactory = clientFactory ?? HttpClient.new;

  final Duration timeout;
  final HttpClient Function() _clientFactory;

  @override
  Future<ConnectivityPingResult> ping(ConnectivityPingRequest request) async {
    final normalizedUrl = ConnectivityCheckSettings.normalizeUrl(request.url);
    final target = ConnectivityCheckTarget.tryParse(normalizedUrl);
    if (target == null) {
      return ConnectivityPingResult.failure(
        error: ConnectivityCheckSettings.validateUrl(normalizedUrl),
      );
    }
    final effectiveTimeout = request.timeoutMs > 0
        ? Duration(
            milliseconds: ConnectivityCheckSettings.normalizeTimeoutMs(
              request.timeoutMs,
            ),
          )
        : timeout;
    return switch (target) {
      HttpCheckTarget() => _fetch(target.uri, request, effectiveTimeout),
      TcpCheckTarget() => _dial(target, request, effectiveTimeout),
    };
  }

  /// An HTTP endpoint: fetched, drained, and judged by its status.
  Future<ConnectivityPingResult> _fetch(
    Uri uri,
    ConnectivityPingRequest request,
    Duration effectiveTimeout,
  ) async {
    final client = _clientFactory();
    client.connectionTimeout = effectiveTimeout;
    client.findProxy = (_) => _proxyDirectiveFor(request);

    final watch = Stopwatch()..start();

    try {
      final httpRequest = await client.getUrl(uri).timeout(effectiveTimeout);
      httpRequest.followRedirects = true;
      final response = await httpRequest.close().timeout(effectiveTimeout);
      await response.drain<void>().timeout(effectiveTimeout);
      watch.stop();

      final code = response.statusCode;
      if (code >= 200 && code < 400) {
        return ConnectivityPingResult.success(
          latencyMs: watch.elapsedMilliseconds,
          statusCode: code,
        );
      }
      return ConnectivityPingResult.failure(
        statusCode: code,
        error: 'HTTP $code',
      );
    } on TimeoutException {
      return ConnectivityPingResult.failure(error: 'Timed out');
    } on SocketException catch (e) {
      return ConnectivityPingResult.failure(error: e.message);
    } on HttpException catch (e) {
      return ConnectivityPingResult.failure(error: e.message);
    } catch (e) {
      return ConnectivityPingResult.failure(error: '$e');
    } finally {
      client.close(force: true);
    }
  }

  /// A bare address: a socket opened, timed, and closed again.
  ///
  /// This is what makes a gateway or a resolver checkable. Nothing is sent and
  /// nothing is read -- the handshake completing is the whole answer, and it is
  /// the closest thing to a ping available without a raw socket, which Android
  /// does not hand to an unprivileged application.
  Future<ConnectivityPingResult> _dial(
    TcpCheckTarget target,
    ConnectivityPingRequest request,
    Duration effectiveTimeout,
  ) async {
    final watch = Stopwatch()..start();
    Socket? socket;
    try {
      if (request.route == ConnectivityPingRoute.direct) {
        socket = await Socket.connect(
          target.host,
          target.port,
          timeout: effectiveTimeout,
        );
        watch.stop();
        return ConnectivityPingResult.success(
          latencyMs: watch.elapsedMilliseconds,
        );
      }
      // Proxy-only mode has no route of its own to dial down, so the socket is
      // asked for through the tunnel's own HTTP proxy with a CONNECT. What is
      // timed is the same thing either way: the far host accepting.
      final proxy = _proxyEndpoint(request);
      socket = await Socket.connect(
        proxy.host,
        proxy.port,
        timeout: effectiveTimeout,
      );
      socket.write(
        'CONNECT ${target.authority} HTTP/1.1\r\n'
        'Host: ${target.authority}\r\n'
        'Proxy-Connection: close\r\n\r\n',
      );
      await socket.flush().timeout(effectiveTimeout);
      final status = await _proxyConnectStatus(
        socket,
      ).timeout(effectiveTimeout);
      watch.stop();
      if (status != null && status >= 200 && status < 300) {
        return ConnectivityPingResult.success(
          latencyMs: watch.elapsedMilliseconds,
          statusCode: status,
        );
      }
      return ConnectivityPingResult.failure(
        statusCode: status,
        error: status == null ? 'Proxy refused CONNECT' : 'CONNECT $status',
      );
    } on TimeoutException {
      return ConnectivityPingResult.failure(error: 'Timed out');
    } on SocketException catch (e) {
      return ConnectivityPingResult.failure(error: e.message);
    } catch (e) {
      return ConnectivityPingResult.failure(error: '$e');
    } finally {
      socket?.destroy();
    }
  }

  /// The status of the proxy's answer to CONNECT, or null if it did not give
  /// one. Only the first line is read; the tunnel is torn down straight after.
  static Future<int?> _proxyConnectStatus(Socket socket) async {
    final buffer = StringBuffer();
    await for (final chunk in socket) {
      buffer.write(String.fromCharCodes(chunk));
      final text = buffer.toString();
      final end = text.indexOf('\r\n');
      if (end < 0) {
        // A status line longer than this is not one; stop rather than buffer
        // whatever a hostile or broken proxy decides to send.
        if (text.length > 1024) return null;
        continue;
      }
      final parts = text.substring(0, end).split(' ');
      return parts.length < 2 ? null : int.tryParse(parts[1]);
    }
    return null;
  }

  ({String host, int port}) _proxyEndpoint(ConnectivityPingRequest request) {
    final host = request.proxyHost?.trim();
    final port = request.proxyPort;
    if (host == null ||
        host.isEmpty ||
        port == null ||
        port < ProxySettings.minPort ||
        port > ProxySettings.maxPort) {
      throw const SocketException('Invalid local HTTP proxy configuration');
    }
    return (host: host, port: port);
  }

  String _proxyDirectiveFor(ConnectivityPingRequest request) {
    return switch (request.route) {
      ConnectivityPingRoute.direct => 'DIRECT',
      ConnectivityPingRoute.localHttpProxy => () {
        final proxy = _proxyEndpoint(request);
        return 'PROXY ${proxy.host}:${proxy.port}';
      }(),
    };
  }
}
