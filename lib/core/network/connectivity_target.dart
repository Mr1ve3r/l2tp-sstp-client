// What a connectivity check aims at (SPEC 7.2). No dependencies on purpose:
// both the settings model and the checker itself need to read this, and a
// third file is what keeps them from having to import each other.

/// The endpoint a connectivity check measures.
///
/// Two shapes, because two questions are worth asking. An HTTP endpoint answers
/// "does the internet work through this tunnel", which is what a captive-portal
/// probe like `generate_204` is for. A bare address answers "can I reach this
/// host at all", which is the only question you can ask of a gateway, a DNS
/// resolver, or an internal server that speaks no HTTP — and the case an
/// administrator handing out a set usually has.
sealed class ConnectivityCheckTarget {
  const ConnectivityCheckTarget();

  /// The port a bare address is dialled on when it names none.
  ///
  /// 443 rather than 80: a host that answers anything at all these days answers
  /// TLS, and a filtered port would report the host unreachable when it is not.
  static const int defaultPort = 443;

  /// The target [text] names, or null if it names none.
  ///
  /// Anything with an `http` or `https` scheme is an HTTP endpoint. Anything
  /// else is read as `host` or `host:port` — `10.0.0.1`, `probe.acme.internal`,
  /// `10.0.0.1:53`, `[2001:db8::1]:853`. A scheme this application cannot
  /// speak is refused rather than guessed at.
  static ConnectivityCheckTarget? tryParse(String text) {
    final trimmed = text.trim();
    if (trimmed.isEmpty) return null;
    if (_hasScheme(trimmed)) {
      final uri = Uri.tryParse(trimmed);
      if (uri == null || uri.host.isEmpty) return null;
      final scheme = uri.scheme.toLowerCase();
      if (scheme != 'http' && scheme != 'https') return null;
      return HttpCheckTarget(uri);
    }
    return _parseAddress(trimmed);
  }

  /// Whether [text] starts with something shaped like `scheme:`.
  ///
  /// Checked before parsing rather than after, because `Uri.parse` reads
  /// `10.0.0.1:53` as the scheme `10.0.0.1` — the port of a bare address and
  /// the colon of a scheme look the same until you know a scheme cannot begin
  /// with a digit.
  static bool _hasScheme(String text) =>
      RegExp(r'^[A-Za-z][A-Za-z0-9+.\-]*:').hasMatch(text);

  static TcpCheckTarget? _parseAddress(String text) {
    // A bracketed IPv6 literal, with or without a port: the brackets are what
    // separate the address's own colons from the one before the port.
    final bracketed = RegExp(r'^\[([^\]]+)\](?::(\d+))?$').firstMatch(text);
    if (bracketed != null) {
      return _tcpTarget(bracketed.group(1)!, bracketed.group(2));
    }
    final colons = ':'.allMatches(text).length;
    if (colons > 1) {
      // More than one colon and no brackets is a bare IPv6 address; there is
      // no port in it, because there would be no way to tell which colon.
      return _tcpTarget(text, null);
    }
    if (colons == 1) {
      final parts = text.split(':');
      return _tcpTarget(parts.first, parts.last);
    }
    return _tcpTarget(text, null);
  }

  static TcpCheckTarget? _tcpTarget(String host, String? port) {
    final trimmedHost = host.trim();
    if (trimmedHost.isEmpty || trimmedHost.contains('/')) return null;
    if (port == null) {
      return TcpCheckTarget(host: trimmedHost, port: defaultPort);
    }
    final parsed = int.tryParse(port);
    if (parsed == null || parsed < 1 || parsed > 65535) return null;
    return TcpCheckTarget(host: trimmedHost, port: parsed);
  }
}

/// An endpoint fetched over HTTP, whose status code is part of the answer.
final class HttpCheckTarget extends ConnectivityCheckTarget {
  const HttpCheckTarget(this.uri);

  final Uri uri;

  @override
  bool operator ==(Object other) =>
      other is HttpCheckTarget && uri == other.uri;

  @override
  int get hashCode => uri.hashCode;

  @override
  String toString() => uri.toString();
}

/// A host and port a socket is opened to, timed and closed again.
final class TcpCheckTarget extends ConnectivityCheckTarget {
  const TcpCheckTarget({required this.host, required this.port});

  final String host;
  final int port;

  /// `host:port`, with an IPv6 address bracketed the way a proxy expects it.
  String get authority => host.contains(':') ? '[$host]:$port' : '$host:$port';

  @override
  bool operator ==(Object other) =>
      other is TcpCheckTarget && host == other.host && port == other.port;

  @override
  int get hashCode => Object.hash(host, port);

  @override
  String toString() => authority;
}
