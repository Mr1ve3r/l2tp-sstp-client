import 'package:flutter_test/flutter_test.dart';
import 'package:tunnel_forge/core/logging/log_entry.dart';
import 'package:tunnel_forge/core/vpn_protocol.dart';
import 'package:tunnel_forge/features/home/presentation/bloc/logs_bloc.dart';

LogEntry _entry(VpnProtocol? protocol, {LogLevel level = LogLevel.info}) {
  return LogEntry(
    timestamp: DateTime(2026, 1, 1),
    level: level,
    source: LogSource.kotlin,
    tag: 'TunnelState',
    message: 'line',
    protocol: protocol,
  );
}

void main() {
  test('an unknown protocol name reads as none', () {
    expect(VpnProtocol.parse('l2tp'), VpnProtocol.l2tp);
    expect(VpnProtocol.parse(' SSTP '), VpnProtocol.sstp);
    expect(VpnProtocol.parse('wireguard'), isNull);
    expect(VpnProtocol.parse(null), isNull);
  });

  test('the protocol filter narrows one buffer to one engine', () {
    final entries = [
      _entry(VpnProtocol.l2tp),
      _entry(VpnProtocol.sstp),
      _entry(null),
    ];

    expect(
      LogsState(entries: entries).visibleLogs.length,
      3,
      reason: 'ALL shows every line, including lines outside a session',
    );
    expect(
      LogsState(
        entries: entries,
        protocolFilter: VpnProtocolFilter.sstp,
      ).visibleLogs.single.protocol,
      VpnProtocol.sstp,
    );
  });

  test('the level and protocol filters both apply', () {
    final entries = [
      _entry(VpnProtocol.sstp, level: LogLevel.debug),
      _entry(VpnProtocol.sstp, level: LogLevel.error),
      _entry(VpnProtocol.l2tp, level: LogLevel.error),
    ];

    final visible = LogsState(
      entries: entries,
      level: LogDisplayLevel.error,
      protocolFilter: VpnProtocolFilter.sstp,
    ).visibleLogs;

    expect(visible.length, 1);
    expect(visible.single.level, LogLevel.error);
    expect(visible.single.protocol, VpnProtocol.sstp);
  });

  test('an exported line names the engine it came from', () {
    expect(_entry(VpnProtocol.sstp).toPlainText(), contains('kotlin/SSTP/'));
    expect(_entry(null).toPlainText(), contains('kotlin/TunnelState'));
  });
}
