import 'package:equatable/equatable.dart';

import 'package:tunnel_forge/core/vpn_protocol.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/tunnel/data/vpn_contract.dart';

/// What the running session negotiated, as the status screen shows it
/// (SPEC 9.1.7).
class TunnelSession extends Equatable {
  const TunnelSession({
    required this.protocol,
    required this.address,
    required this.dnsServers,
    required this.mtu,
    required this.since,
    required this.rxBytes,
    required this.txBytes,
    this.proxyHost,
  });

  final VpnProtocol protocol;
  final String address;
  final List<String> dnsServers;
  final int mtu;

  /// When the tunnel came up, for the session timer.
  final DateTime since;
  final int rxBytes;
  final int txBytes;

  /// The HTTP proxy the transport goes through, or null for a direct one.
  final String? proxyHost;

  /// `null` when the payload carries no session, which is every state but
  /// connected.
  static TunnelSession? tryFromMap(Map<Object?, Object?> raw) {
    final protocol = VpnProtocol.parse(raw[VpnContract.argTunnelProtocol]);
    final address = raw[VpnContract.argSessionAddress]?.toString() ?? '';
    if (protocol == null || address.isEmpty) return null;
    final proxyHost = raw[VpnContract.argSessionProxyHost]?.toString();
    return TunnelSession(
      protocol: protocol,
      address: address,
      dnsServers:
          (raw[VpnContract.argSessionDns] as List<Object?>?)
              ?.map((entry) => entry?.toString() ?? '')
              .where((entry) => entry.isNotEmpty)
              .toList(growable: false) ??
          const <String>[],
      mtu: (raw[VpnContract.argSessionMtu] as num?)?.toInt() ?? 0,
      since: DateTime.fromMillisecondsSinceEpoch(
        (raw[VpnContract.argSessionSince] as num?)?.toInt() ?? 0,
      ),
      rxBytes: (raw[VpnContract.argSessionRxBytes] as num?)?.toInt() ?? 0,
      txBytes: (raw[VpnContract.argSessionTxBytes] as num?)?.toInt() ?? 0,
      proxyHost: proxyHost == null || proxyHost.isEmpty ? null : proxyHost,
    );
  }

  @override
  List<Object?> get props => [
    protocol,
    address,
    dnsServers,
    mtu,
    since,
    rxBytes,
    txBytes,
    proxyHost,
  ];
}

class TunnelRuntimeState extends Equatable {
  const TunnelRuntimeState({
    required this.state,
    required this.detail,
    required this.connectionMode,
    this.attemptId = '',
    this.proxyExposure,
    this.session,
  });

  const TunnelRuntimeState.idle()
    : this(
        state: VpnTunnelState.stopped,
        detail: 'Idle',
        connectionMode: ConnectionMode.vpnTunnel,
      );

  final String state;
  final String detail;
  final ConnectionMode connectionMode;
  final String attemptId;
  final ProxyExposure? proxyExposure;

  /// Present only while the tunnel is up.
  final TunnelSession? session;

  @override
  List<Object?> get props => [
    state,
    detail,
    connectionMode,
    attemptId,
    proxyExposure,
    session,
  ];
}
