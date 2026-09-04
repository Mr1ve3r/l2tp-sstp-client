/// The VPN protocol a profile, a session, or a log line belongs to.
///
/// One enum rather than one per layer: the host dispatches on this string
/// (SPEC 7.1.1), and the same value comes back on every log line so a single
/// buffer holding both engines can still be filtered (SPEC 7.1.7).
enum VpnProtocol {
  l2tp('L2TP', 'l2tp'),
  sstp('SSTP', 'sstp');

  const VpnProtocol(this.label, this.wireValue);

  /// Short name shown in the UI and in exported logs.
  final String label;

  /// The value the method channel and the start intent carry.
  final String wireValue;

  /// The protocol [raw] names, or null when it names none this build knows.
  static VpnProtocol? parse(Object? raw) {
    final value = raw?.toString().trim().toLowerCase();
    for (final protocol in VpnProtocol.values) {
      if (protocol.wireValue == value) return protocol;
    }
    return null;
  }
}

/// The protocol filter over the log view.
enum VpnProtocolFilter {
  all('ALL', null),
  l2tp('L2TP', VpnProtocol.l2tp),
  sstp('SSTP', VpnProtocol.sstp);

  const VpnProtocolFilter(this.label, this.protocol);

  final String label;
  final VpnProtocol? protocol;

  /// Lines with no protocol belong to no session and show under [all] only.
  bool includes(VpnProtocol? entry) => protocol == null || entry == protocol;
}
