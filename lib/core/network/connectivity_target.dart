// What a connectivity check aims at (SPEC 7.2). No dependencies on purpose:
// both the settings model and the checker itself need to read this, and a
// third file is what keeps them from having to import each other.

/// The endpoint a connectivity check measures.
///
/// Two shapes, because two questions are worth asking. An HTTP endpoint answers
/// "does the internet work through this tunnel", which is what a captive-portal
/// probe like `generate_204` is for. A host and port answers "can I reach this
/// service at all", which is the only question you can ask of a file share, a
/// gateway, a resolver, or anything else that speaks no HTTP — and the case an
/// administrator handing out a set usually has.
sealed class ConnectivityCheckTarget {
  const ConnectivityCheckTarget();

  /// The port a bare address is dialled on when it names neither a port nor a
  /// scheme.
  ///
  /// 443 rather than 80: a host that answers anything at all these days answers
  /// TLS, and a filtered port would report the host unreachable when it is not.
  static const int defaultPort = 443;

  /// Schemes that name a service, and the port each one is spoken on.
  ///
  /// This is the whole of the "flexible" part: a scheme is a name for a port
  /// number, so `smb://disk.corp.example` says what it means and needs nothing
  /// remembered. `tcp://` is the escape hatch for a service not listed here,
  /// and every one of them takes an explicit `:port` that wins over the
  /// default.
  static const Map<String, int> servicePorts = <String, int>{
    'tcp': defaultPort,
    'smb': 445,
    'rdp': 3389,
    'ssh': 22,
    'dns': 53,
    'ldap': 389,
    'ldaps': 636,
    'imaps': 993,
    'smtps': 465,
    'vnc': 5900,
    'postgres': 5432,
    'mysql': 3306,
  };

  /// The target [text] names, or null if it names none.
  ///
  /// Four shapes are accepted:
  ///
  /// - `https://host/path` — fetched, and judged by its status code.
  /// - `smb://host`, `rdp://host`, `tcp://host:8443` — dialled on the port the
  ///   scheme stands for, or on the one written after it.
  /// - `host:445` — dialled on that port.
  /// - `host` — dialled on [defaultPort].
  ///
  /// A scheme that is neither HTTP nor in [servicePorts] is refused rather than
  /// guessed at: dialling an arbitrary port because a word was spelled in front
  /// of a host would report reachability for something nobody asked about.
  static ConnectivityCheckTarget? tryParse(String text) {
    final trimmed = text.trim();
    if (trimmed.isEmpty) return null;
    final scheme = _schemeOf(trimmed);
    if (scheme == null) return _parseAddress(trimmed, defaultPort);
    if (scheme == 'http' || scheme == 'https') {
      final uri = Uri.tryParse(trimmed);
      if (uri == null || uri.host.isEmpty) return null;
      return HttpCheckTarget(uri);
    }
    final port = servicePorts[scheme];
    if (port == null) return null;
    // Whatever follows `scheme://`, read the same way a bare address is. A
    // path is refused there, which is right here too: `smb://host/share` names
    // a share, and reaching the server is all that can be measured.
    final rest = trimmed
        .substring(scheme.length + 1)
        .replaceFirst(RegExp(r'^/{0,2}'), '');
    return _parseAddress(rest, port);
  }

  /// The scheme [text] starts with, lower-cased, or null if it starts with
  /// none.
  ///
  /// Checked by shape before anything is parsed, because `Uri.parse` reads
  /// `10.0.0.1:53` as the scheme `10.0.0.1` — the port of a bare address and
  /// the colon of a scheme look the same until you know a scheme cannot begin
  /// with a digit.
  static String? _schemeOf(String text) {
    final match = RegExp(r'^([A-Za-z][A-Za-z0-9+.\-]*):').firstMatch(text);
    return match?.group(1)?.toLowerCase();
  }

  static TcpCheckTarget? _parseAddress(String text, int fallbackPort) {
    final trimmed = text.trim();
    if (trimmed.isEmpty) return null;
    // A bracketed IPv6 literal, with or without a port: the brackets are what
    // separate the address's own colons from the one before the port.
    final bracketed = RegExp(r'^\[([^\]]+)\](?::(\d+))?$').firstMatch(trimmed);
    if (bracketed != null) {
      return _tcpTarget(bracketed.group(1)!, bracketed.group(2), fallbackPort);
    }
    final colons = ':'.allMatches(trimmed).length;
    if (colons > 1) {
      // More than one colon and no brackets is a bare IPv6 address; there is
      // no port in it, because there would be no way to tell which colon.
      return _tcpTarget(trimmed, null, fallbackPort);
    }
    if (colons == 1) {
      final parts = trimmed.split(':');
      return _tcpTarget(parts.first, parts.last, fallbackPort);
    }
    return _tcpTarget(trimmed, null, fallbackPort);
  }

  static TcpCheckTarget? _tcpTarget(String host, String? port, int fallback) {
    final trimmedHost = host.trim();
    if (trimmedHost.isEmpty || trimmedHost.contains('/')) return null;
    if (port == null) {
      return TcpCheckTarget(host: trimmedHost, port: fallback);
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
