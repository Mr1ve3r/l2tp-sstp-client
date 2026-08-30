import 'dart:async';

import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';
import 'package:flutter/services.dart';

import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';
import 'package:tunnel_forge/core/logging/log_entry.dart';
import 'package:tunnel_forge/features/tunnel/data/vpn_contract.dart';
import 'package:tunnel_forge/features/tunnel/domain/engine_error_text.dart';
import 'package:tunnel_forge/features/tunnel/domain/tunnel_runtime_state.dart';
import '../../../home/domain/home_models.dart';
import '../../../home/domain/home_repositories.dart';
import '../../../home/domain/log_redaction.dart';

sealed class TunnelEvent extends Equatable {
  const TunnelEvent();

  @override
  List<Object?> get props => const [];
}

final class TunnelStarted extends TunnelEvent {
  const TunnelStarted();
}

final class TunnelConnectRequested extends TunnelEvent {
  const TunnelConnectRequested(this.request);

  final TunnelConnectRequest request;

  @override
  List<Object?> get props => [request];
}

final class TunnelDisconnectRequested extends TunnelEvent {
  const TunnelDisconnectRequested();
}

final class TunnelHostStateReceived extends TunnelEvent {
  const TunnelHostStateReceived(this.update);

  final TunnelHostUpdate update;

  @override
  List<Object?> get props => [update];
}

final class TunnelEngineLogReceived extends TunnelEvent {
  const TunnelEngineLogReceived(this.message);

  final EngineLogMessage message;

  @override
  List<Object?> get props => [message];
}

final class TunnelProxyExposureReceived extends TunnelEvent {
  const TunnelProxyExposureReceived(this.exposure);

  final ProxyExposure exposure;

  @override
  List<Object?> get props => [exposure];
}

final class TunnelAwaitTimedOut extends TunnelEvent {
  const TunnelAwaitTimedOut(this.attemptId);

  final String attemptId;

  @override
  List<Object?> get props => [attemptId];
}

final class TunnelDisconnectTimedOut extends TunnelEvent {
  const TunnelDisconnectTimedOut(this.attemptId);

  final String attemptId;

  @override
  List<Object?> get props => [attemptId];
}

/// One reading of the live session, taken while the tunnel is up.
final class TunnelSessionPolled extends TunnelEvent {
  const TunnelSessionPolled();
}

class TunnelState extends Equatable {
  const TunnelState({
    this.busy = false,
    this.tunnelUp = false,
    this.awaitingTunnel = false,
    this.stopRequested = false,
    this.timedOutThisAttempt = false,
    this.connectionMode = ConnectionMode.vpnTunnel,
    this.activeAttemptId,
    this.connectStartedAt,
    this.proxyExposure,
    this.session,
    this.message,
  });

  final bool busy;
  final bool tunnelUp;
  final bool awaitingTunnel;
  final bool stopRequested;
  final bool timedOutThisAttempt;
  final ConnectionMode connectionMode;
  final String? activeAttemptId;
  final DateTime? connectStartedAt;
  final ProxyExposure? proxyExposure;

  /// What the running tunnel negotiated, refreshed while it is up (SPEC 9.1.7).
  final TunnelSession? session;
  final HomeMessage? message;

  TunnelState copyWith({
    bool? busy,
    bool? tunnelUp,
    bool? awaitingTunnel,
    bool? stopRequested,
    bool? timedOutThisAttempt,
    ConnectionMode? connectionMode,
    String? activeAttemptId,
    bool clearActiveAttemptId = false,
    DateTime? connectStartedAt,
    bool clearConnectStartedAt = false,
    ProxyExposure? proxyExposure,
    bool clearProxyExposure = false,
    TunnelSession? session,
    bool clearSession = false,
    HomeMessage? message,
    bool clearMessage = false,
  }) {
    return TunnelState(
      busy: busy ?? this.busy,
      tunnelUp: tunnelUp ?? this.tunnelUp,
      awaitingTunnel: awaitingTunnel ?? this.awaitingTunnel,
      stopRequested: stopRequested ?? this.stopRequested,
      timedOutThisAttempt: timedOutThisAttempt ?? this.timedOutThisAttempt,
      connectionMode: connectionMode ?? this.connectionMode,
      activeAttemptId: clearActiveAttemptId
          ? null
          : (activeAttemptId ?? this.activeAttemptId),
      connectStartedAt: clearConnectStartedAt
          ? null
          : (connectStartedAt ?? this.connectStartedAt),
      proxyExposure: clearProxyExposure
          ? null
          : (proxyExposure ?? this.proxyExposure),
      session: clearSession ? null : (session ?? this.session),
      message: clearMessage ? null : (message ?? this.message),
    );
  }

  @override
  List<Object?> get props => [
    busy,
    tunnelUp,
    awaitingTunnel,
    stopRequested,
    timedOutThisAttempt,
    connectionMode,
    activeAttemptId,
    connectStartedAt,
    proxyExposure,
    session,
    message,
  ];
}

class TunnelBloc extends Bloc<TunnelEvent, TunnelState> {
  TunnelBloc(this._tunnelRepository, this._logsRepository)
    : super(const TunnelState()) {
    on<TunnelStarted>(_onStarted);
    on<TunnelConnectRequested>(_onConnectRequested);
    on<TunnelDisconnectRequested>(_onDisconnectRequested);
    on<TunnelHostStateReceived>(_onHostStateReceived);
    on<TunnelEngineLogReceived>(_onEngineLogReceived);
    on<TunnelProxyExposureReceived>(_onProxyExposureReceived);
    on<TunnelSessionPolled>(_onSessionPolled);
    on<TunnelAwaitTimedOut>(_onAwaitTimedOut);
    on<TunnelDisconnectTimedOut>(_onDisconnectTimedOut);
  }

  final TunnelRepository _tunnelRepository;
  final LogsRepository _logsRepository;
  StreamSubscription<TunnelHostUpdate>? _tunnelStatesSub;
  StreamSubscription<EngineLogMessage>? _engineLogsSub;
  StreamSubscription<ProxyExposure>? _proxyExposureSub;
  Future<void>? _runtimeBootstrapFuture;
  Timer? _awaitTimer;
  Timer? _disconnectTimer;
  Timer? _sessionTimer;
  int _messageId = 0;

  Future<void> _onStarted(
    TunnelStarted event,
    Emitter<TunnelState> emit,
  ) async {
    _tunnelStatesSub?.cancel();
    _engineLogsSub?.cancel();
    _proxyExposureSub?.cancel();
    _tunnelStatesSub = _tunnelRepository.tunnelStates.listen((update) {
      add(TunnelHostStateReceived(update));
    });
    _engineLogsSub = _tunnelRepository.engineLogs.listen((message) {
      add(TunnelEngineLogReceived(message));
    });
    _proxyExposureSub = _tunnelRepository.proxyExposures.listen((exposure) {
      add(TunnelProxyExposureReceived(exposure));
    });
    final bootstrap = _bootstrapRuntimeState(emit);
    _runtimeBootstrapFuture = bootstrap;
    try {
      await bootstrap;
    } finally {
      if (identical(_runtimeBootstrapFuture, bootstrap)) {
        _runtimeBootstrapFuture = null;
      }
    }
  }

  Future<void> _onConnectRequested(
    TunnelConnectRequested event,
    Emitter<TunnelState> emit,
  ) async {
    final runtimeBootstrap = _runtimeBootstrapFuture;
    if (runtimeBootstrap != null) {
      await runtimeBootstrap;
    }
    if (state.busy ||
        state.stopRequested ||
        state.tunnelUp ||
        (state.awaitingTunnel && !state.tunnelUp)) {
      return;
    }
    final request = event.request;
    final proxyMode = request.connectionMode == ConnectionMode.proxyOnly;
    final host = request.server.trim();
    if (!proxyMode && host.isEmpty) {
      _toast(emit, AppText.current.enterServerHostnameOrAddress, error: true);
      _logWarning('Connect blocked: empty server', tag: 'tunnel');
      return;
    }
    if (proxyMode && request.activeProfileId == null) {
      _toast(emit, AppText.current.selectSavedProfileForProxy, error: true);
      _logWarning(
        'Connect blocked: proxy mode requires a saved profile',
        tag: 'tunnel',
      );
      return;
    }
    if (request.proxySettings.httpPort == request.proxySettings.socksPort) {
      _toast(emit, AppText.current.httpAndSocksPortsMustDiffer, error: true);
      _logWarning(
        'Connect blocked: proxy ports collide http=${request.proxySettings.httpPort} socks=${request.proxySettings.socksPort}',
        tag: 'tunnel',
      );
      return;
    }
    final splitTunnelSettings = request.splitTunnelSettings;
    if (!proxyMode &&
        splitTunnelSettings.enabled &&
        splitTunnelSettings.mode == SplitTunnelMode.inclusive &&
        splitTunnelSettings.inclusivePackages.isEmpty) {
      _toast(
        emit,
        AppText.current.selectAppsForInclusiveSplitTunnel,
        error: true,
      );
      _logWarning(
        'Connect blocked: inclusive split tunneling is enabled with an empty app list',
        tag: 'tunnel',
      );
      return;
    }

    final attemptId = _newAttemptId();
    emit(
      state.copyWith(
        busy: true,
        connectionMode: request.connectionMode,
        activeAttemptId: attemptId,
        connectStartedAt: DateTime.now(),
        stopRequested: false,
        timedOutThisAttempt: false,
        clearProxyExposure: true,
        clearMessage: true,
      ),
    );

    try {
      if (!proxyMode) {
        _logDebug(
          'Requesting VPN permission (if needed)... attempt=$attemptId',
          tag: 'tunnel',
        );
        final ok = await _tunnelRepository.prepareVpn();
        if (!ok) {
          _logWarning(
            'VPN permission denied or cancelled attempt=$attemptId',
            tag: 'tunnel',
          );
          _toast(emit, AppText.current.vpnPermissionRequired, error: true);
          emit(
            state.copyWith(
              busy: false,
              clearActiveAttemptId: true,
              clearConnectStartedAt: true,
              clearProxyExposure: true,
              clearSession: true,
            ),
          );
          return;
        }
        _logDebug('VPN permission OK attempt=$attemptId', tag: 'tunnel');
      } else {
        _logDebug(
          'Starting local proxy mode without Android VPN permission... attempt=$attemptId',
          tag: 'tunnel',
        );
      }

      final dnsLog = request.dnsAutomatic
          ? 'automatic(ppp)'
          : request.dnsServers
                .map((entry) => '${entry.host}[${entry.protocol.shortLabel}]')
                .join(', ');
      _logDebug(
        'Profile: server=$host user=${request.user.isEmpty ? '(empty)' : request.user} dns=$dnsLog mtu=${request.mtu} '
        'psk=${request.psk.isEmpty ? 'off (cleartext L2TP if server allows)' : 'on'} '
        'mode=${request.connectionMode.jsonValue} splitTunnelEnabled=${splitTunnelSettings.enabled} splitTunnelMode=${splitTunnelSettings.mode.jsonValue} '
        'inclusiveApps=${splitTunnelSettings.inclusivePackages.length} exclusiveApps=${splitTunnelSettings.exclusivePackages.length} '
        'http=${request.proxySettings.httpPort} socks=${request.proxySettings.socksPort} lan=${request.proxySettings.allowLanConnections ? 'on' : 'off'} '
        'attempt=$attemptId',
        tag: 'tunnel',
      );
      await _tunnelRepository.connect(
        TunnelConnectRequest(
          activeProfileId: request.activeProfileId,
          profileName: request.profileName,
          server: request.server,
          user: request.user,
          password: request.password,
          psk: request.psk,
          dnsAutomatic: request.dnsAutomatic,
          dnsServers: request.dnsServers,
          mtu: request.mtu,
          connectionMode: request.connectionMode,
          splitTunnelSettings: request.splitTunnelSettings,
          proxySettings: request.proxySettings,
          attemptId: attemptId,
        ),
      );
      _logDebug(
        'Connect acknowledged; waiting for ${proxyMode ? 'proxy readiness' : 'TUN'} from Android... attempt=$attemptId',
        tag: 'tunnel',
      );
      emit(
        state.copyWith(
          busy: false,
          awaitingTunnel: true,
          tunnelUp: false,
          stopRequested: false,
        ),
      );
      _scheduleAwaitTimeout(attemptId);
      _toast(
        emit,
        proxyMode ? AppText.current.startingProxy : AppText.current.connecting,
      );
    } on PlatformException catch (error) {
      _logError(
        'Platform error: ${error.code} ${error.message ?? ''} attempt=$attemptId',
        tag: 'tunnel',
      );
      _toast(emit, error.message ?? error.code, error: true);
      emit(
        state.copyWith(
          busy: false,
          clearActiveAttemptId: true,
          clearConnectStartedAt: true,
          clearProxyExposure: true,
        ),
      );
    }
  }

  Future<void> _onDisconnectRequested(
    TunnelDisconnectRequested event,
    Emitter<TunnelState> emit,
  ) async {
    if (state.busy || state.stopRequested) return;
    final cancelPendingConnect = state.awaitingTunnel && !state.tunnelUp;
    final disconnectAttemptId = state.activeAttemptId ?? '';
    emit(state.copyWith(busy: true, stopRequested: true, clearMessage: true));
    _logDebug(
      cancelPendingConnect
          ? 'Cancel requested while waiting for tunnel...'
          : 'Disconnect requested...',
      tag: 'tunnel',
    );
    try {
      await _tunnelRepository.disconnect(
        connectionMode: state.connectionMode,
        attemptId: disconnectAttemptId,
      );
      _logDebug(
        cancelPendingConnect
            ? 'Cancel dispatched; waiting for Android stopped event...'
            : 'Disconnect dispatched; waiting for Android stopped event...',
        tag: 'tunnel',
      );
      _cancelAwaitTimer();
      _scheduleDisconnectTimeout(
        disconnectAttemptId,
        cancelPendingConnect: cancelPendingConnect,
      );
    } on PlatformException catch (error) {
      _logError(
        'Disconnect error: ${error.code} ${error.message ?? ''}',
        tag: 'tunnel',
      );
      _toast(emit, error.message ?? error.code, error: true);
      emit(state.copyWith(stopRequested: false));
      _cancelDisconnectTimer();
    } finally {
      emit(state.copyWith(busy: false));
    }
  }

  void _onHostStateReceived(
    TunnelHostStateReceived event,
    Emitter<TunnelState> emit,
  ) {
    final update = event.update;
    final t = AppText.current;
    final currentAttemptId = state.activeAttemptId;
    final proxyMode = state.connectionMode == ConnectionMode.proxyOnly;
    if (update.attemptId.isNotEmpty) {
      if (currentAttemptId == null) {
        _logWarning(
          'Ignoring stale Android event without active attempt: state=${update.state} attempt=${update.attemptId}',
          source: LogSource.kotlin,
          tag: 'TunnelState',
        );
        return;
      }
      if (update.attemptId != currentAttemptId) {
        _logWarning(
          'Ignoring stale Android event: state=${update.state} attempt=${update.attemptId} active=$currentAttemptId',
          source: LogSource.kotlin,
          tag: 'TunnelState',
        );
        return;
      }
    }
    if (update.attemptId.isEmpty &&
        currentAttemptId != null &&
        proxyMode &&
        _isTerminalTunnelState(update.state)) {
      _logWarning(
        'Ignoring untagged Android terminal event while active attempt is $currentAttemptId: state=${update.state}',
        source: LogSource.kotlin,
        tag: 'TunnelState',
      );
      return;
    }
    if (state.timedOutThisAttempt &&
        (update.state == VpnTunnelState.connected ||
            update.state == VpnTunnelState.failed)) {
      final startedAt = state.connectStartedAt;
      final elapsed = startedAt == null
          ? null
          : DateTime.now().difference(startedAt);
      final attempt = state.activeAttemptId;
      _logWarning(
        'Late Android event after timeout: state=${update.state}${attempt == null ? '' : ' attempt=$attempt'}${elapsed == null ? '' : ' after_ms=${elapsed.inMilliseconds}'}',
        source: LogSource.kotlin,
        tag: 'TunnelState',
      );
    }
    switch (update.state) {
      case VpnTunnelState.connecting:
        _logInfo(
          'Android${state.activeAttemptId == null ? '' : ' attempt=${state.activeAttemptId!}'}: ${update.detail}',
          source: LogSource.kotlin,
          tag: 'TunnelState',
        );
        if (state.awaitingTunnel && !state.tunnelUp && !state.stopRequested) {
          _scheduleAwaitTimeout(state.activeAttemptId ?? '');
        }
        break;
      case VpnTunnelState.connected:
        _cancelAwaitTimer();
        emit(
          state.copyWith(
            awaitingTunnel: false,
            tunnelUp: true,
            stopRequested: state.stopRequested,
            timedOutThisAttempt: false,
          ),
        );
        _startSessionPolling();
        _logInfo(
          'Android${state.activeAttemptId == null ? '' : ' attempt=${state.activeAttemptId!}'}: ${proxyMode ? 'proxy ready' : 'TUN is up'}: ${update.detail}',
          source: LogSource.kotlin,
          tag: 'TunnelState',
        );
        if (!state.stopRequested) {
          _toast(
            emit,
            proxyMode && _isProxyPortChangedNotice(update.detail)
                ? update.detail
                : proxyMode
                ? t.proxyReady
                : AppText.current.vpnConnected,
          );
        }
        break;
      case VpnTunnelState.reconnecting:
        // The host is rebuilding the tunnel after a network change (SPEC В.4).
        // No await timer is started: the reconnect has its own attempt limit,
        // and a timeout here would race it and report a failure it recovers
        // from on its own.
        _cancelAwaitTimer();
        emit(
          state.copyWith(
            awaitingTunnel: true,
            tunnelUp: false,
            timedOutThisAttempt: false,
          ),
        );
        _logWarning(
          'Android${state.activeAttemptId == null ? '' : ' attempt=${state.activeAttemptId!}'}: ${update.detail}',
          source: LogSource.kotlin,
          tag: 'TunnelState',
        );
        break;
      case VpnTunnelState.failed:
        _cancelAwaitTimer();
        _cancelDisconnectTimer();
        if (state.stopRequested && _isShutdownFailure(update.detail)) {
          emit(
            state.copyWith(
              awaitingTunnel: false,
              tunnelUp: false,
              stopRequested: false,
              timedOutThisAttempt: false,
              clearActiveAttemptId: true,
              clearConnectStartedAt: true,
              clearProxyExposure: true,
              clearSession: true,
            ),
          );
          _logInfo(
            'Android${state.activeAttemptId == null ? '' : ' attempt=${state.activeAttemptId!}'}: tunnel stopped during shutdown: ${update.detail}',
            source: LogSource.kotlin,
            tag: 'TunnelState',
          );
          break;
        }
        emit(
          state.copyWith(
            awaitingTunnel: false,
            tunnelUp: false,
            stopRequested: false,
            timedOutThisAttempt: false,
            clearActiveAttemptId: true,
            clearConnectStartedAt: true,
            clearProxyExposure: true,
            clearSession: true,
          ),
        );
        _logError(
          'Android${state.activeAttemptId == null ? '' : ' attempt=${state.activeAttemptId!}'}: tunnel failed: ${update.detail}',
          source: LogSource.kotlin,
          tag: 'TunnelState',
        );
        _toast(
          emit,
          engineErrorText(t, update.errorKey) ??
              (update.detail.isEmpty
                  ? AppText.current.couldNotEstablishTunnel
                  : update.detail),
          error: true,
        );
        break;
      case VpnTunnelState.stopped:
        _cancelAwaitTimer();
        _cancelDisconnectTimer();
        emit(
          state.copyWith(
            awaitingTunnel: false,
            tunnelUp: false,
            stopRequested: false,
            timedOutThisAttempt: false,
            clearActiveAttemptId: true,
            clearConnectStartedAt: true,
            clearProxyExposure: true,
            clearSession: true,
          ),
        );
        _logInfo(
          'Android${state.activeAttemptId == null ? '' : ' attempt=${state.activeAttemptId!}'}: tunnel stopped: ${update.detail}',
          source: LogSource.kotlin,
          tag: 'TunnelState',
        );
        break;
      default:
        _logDebug(
          'Android${state.activeAttemptId == null ? '' : ' attempt=${state.activeAttemptId!}'}: ${update.state}${update.detail.isEmpty ? '' : ': ${update.detail}'}',
          source: LogSource.kotlin,
          tag: 'TunnelState',
        );
    }
  }

  void _onEngineLogReceived(
    TunnelEngineLogReceived event,
    Emitter<TunnelState> emit,
  ) {
    _logsRepository.append(
      LogEntry(
        timestamp: event.message.timestamp,
        level: event.message.level,
        source: event.message.source,
        tag: event.message.tag,
        message: redactLogMessage(event.message.message),
      ),
    );
  }

  void _onProxyExposureReceived(
    TunnelProxyExposureReceived event,
    Emitter<TunnelState> emit,
  ) {
    if (!event.exposure.active) {
      emit(state.copyWith(clearProxyExposure: true));
      return;
    }
    emit(state.copyWith(proxyExposure: event.exposure));
  }

  bool _isShutdownFailure(String detail) {
    final normalized = detail.trim().toLowerCase();
    return normalized == 'local proxy connection was lost.' ||
        normalized == 'local proxy connection was lost' ||
        normalized == 'proxy packet bridge stopped.' ||
        normalized == 'proxy packet bridge stopped' ||
        normalized == 'socket closed';
  }

  bool _isTerminalTunnelState(String tunnelState) {
    return tunnelState == VpnTunnelState.failed ||
        tunnelState == VpnTunnelState.stopped;
  }

  bool _isProxyPortChangedNotice(String detail) {
    return detail.trim().startsWith('Proxy ports changed:');
  }

  void _onAwaitTimedOut(TunnelAwaitTimedOut event, Emitter<TunnelState> emit) {
    if (!state.awaitingTunnel ||
        state.tunnelUp ||
        state.activeAttemptId != event.attemptId) {
      return;
    }
    final startedAt = state.connectStartedAt;
    final elapsed = startedAt == null
        ? null
        : DateTime.now().difference(startedAt);
    _logWarning(
      'Still waiting for Android ${state.connectionMode == ConnectionMode.proxyOnly ? 'proxy readiness' : 'VPN interface'}${event.attemptId.isEmpty ? '' : ' attempt=${event.attemptId}'}${elapsed == null ? '' : ' elapsed_ms=${elapsed.inMilliseconds}'}',
      tag: 'tunnel',
    );
    _scheduleAwaitTimeout(event.attemptId);
  }

  Future<void> _onDisconnectTimedOut(
    TunnelDisconnectTimedOut event,
    Emitter<TunnelState> emit,
  ) async {
    final currentAttemptId = state.activeAttemptId ?? '';
    if (!state.stopRequested || currentAttemptId != event.attemptId) {
      return;
    }
    final runtimeState = await _tunnelRepository.getRuntimeState();
    if (runtimeState.state == VpnTunnelState.stopped) {
      _logWarning(
        'Android stop event did not arrive; runtime snapshot is idle${event.attemptId.isEmpty ? '' : ' attempt=${event.attemptId}'}',
        tag: 'tunnel',
      );
      _cancelDisconnectTimer();
      emit(
        state.copyWith(
          busy: false,
          awaitingTunnel: false,
          tunnelUp: false,
          stopRequested: false,
          timedOutThisAttempt: false,
          clearActiveAttemptId: true,
          clearConnectStartedAt: true,
          clearProxyExposure: true,
        ),
      );
      return;
    }
    _logWarning(
      'Still waiting for Android stopped event; runtime state=${runtimeState.state} mode=${runtimeState.connectionMode.jsonValue}${event.attemptId.isEmpty ? '' : ' attempt=${event.attemptId}'}',
      tag: 'tunnel',
    );
    _scheduleDisconnectTimeout(
      event.attemptId,
      cancelPendingConnect: state.awaitingTunnel && !state.tunnelUp,
    );
  }

  void _scheduleAwaitTimeout(String attemptId) {
    _cancelAwaitTimer();
    _awaitTimer = Timer(const Duration(seconds: 60), () {
      add(TunnelAwaitTimedOut(attemptId));
    });
  }

  void _scheduleDisconnectTimeout(
    String attemptId, {
    required bool cancelPendingConnect,
  }) {
    _cancelDisconnectTimer();
    _disconnectTimer = Timer(
      Duration(seconds: cancelPendingConnect ? 20 : 10),
      () {
        add(TunnelDisconnectTimedOut(attemptId));
      },
    );
  }

  Future<void> _bootstrapRuntimeState(Emitter<TunnelState> emit) async {
    final runtimeState = await _tunnelRepository.getRuntimeState();
    if (runtimeState.state == VpnTunnelState.stopped) {
      return;
    }
    final connected = runtimeState.state == VpnTunnelState.connected;
    final connecting = runtimeState.state == VpnTunnelState.connecting;
    if (!connected && !connecting) {
      return;
    }
    emit(
      state.copyWith(
        busy: false,
        tunnelUp: connected,
        awaitingTunnel: connecting,
        stopRequested: false,
        timedOutThisAttempt: false,
        connectionMode: runtimeState.connectionMode,
        activeAttemptId: runtimeState.attemptId.isEmpty
            ? null
            : runtimeState.attemptId,
        connectStartedAt: connecting ? DateTime.now() : null,
        clearConnectStartedAt: connected,
        proxyExposure: runtimeState.proxyExposure,
        clearProxyExposure: runtimeState.proxyExposure == null,
        session: runtimeState.session,
        clearSession: runtimeState.session == null,
        clearMessage: true,
      ),
    );
    if (connected) _startSessionPolling();
    if (connecting) {
      _scheduleAwaitTimeout(runtimeState.attemptId);
    } else {
      _cancelAwaitTimer();
    }
    _logInfo(
      'Recovered runtime state from Android: state=${runtimeState.state} mode=${runtimeState.connectionMode.jsonValue}${runtimeState.attemptId.isEmpty ? '' : ' attempt=${runtimeState.attemptId}'}',
      tag: 'TunnelState',
    );
  }

  /// Polls the host once a second while the tunnel is up.
  ///
  /// The session's byte counters and its timer move on their own; a state event
  /// only arrives when the tunnel changes, so the status screen has to ask.
  void _startSessionPolling() {
    if (_sessionTimer != null) return;
    add(const TunnelSessionPolled());
    _sessionTimer = Timer.periodic(
      const Duration(seconds: 1),
      (_) => add(const TunnelSessionPolled()),
    );
  }

  void _stopSessionPolling() {
    _sessionTimer?.cancel();
    _sessionTimer = null;
  }

  Future<void> _onSessionPolled(
    TunnelSessionPolled event,
    Emitter<TunnelState> emit,
  ) async {
    if (!state.tunnelUp) {
      _stopSessionPolling();
      if (state.session != null) emit(state.copyWith(clearSession: true));
      return;
    }
    final runtimeState = await _tunnelRepository.getRuntimeState();
    final session = runtimeState.session;
    if (session == null) return;
    emit(state.copyWith(session: session));
  }

  void _cancelAwaitTimer() {
    _awaitTimer?.cancel();
    _awaitTimer = null;
  }

  void _cancelDisconnectTimer() {
    _disconnectTimer?.cancel();
    _disconnectTimer = null;
  }

  String _newAttemptId() {
    final ms = DateTime.now().millisecondsSinceEpoch;
    final randomish = (ms ^ (ms >> 7) ^ (ms << 3)) & 0xFFFFFF;
    return '${ms.toRadixString(36)}-${randomish.toRadixString(36).padLeft(4, '0')}';
  }

  void _logDebug(
    String message, {
    LogSource source = LogSource.dart,
    String tag = 'home',
  }) {
    _appendLogEntry(LogLevel.debug, message, source: source, tag: tag);
  }

  void _logInfo(
    String message, {
    LogSource source = LogSource.dart,
    String tag = 'home',
  }) {
    _appendLogEntry(LogLevel.info, message, source: source, tag: tag);
  }

  void _logWarning(
    String message, {
    LogSource source = LogSource.dart,
    String tag = 'home',
  }) {
    _appendLogEntry(LogLevel.warning, message, source: source, tag: tag);
  }

  void _logError(
    String message, {
    LogSource source = LogSource.dart,
    String tag = 'home',
  }) {
    _appendLogEntry(LogLevel.error, message, source: source, tag: tag);
  }

  void _appendLogEntry(
    LogLevel level,
    String message, {
    required LogSource source,
    required String tag,
  }) {
    _logsRepository.append(
      LogEntry(
        timestamp: DateTime.now(),
        level: level,
        source: source,
        tag: tag,
        message: redactLogMessage(message),
      ),
    );
  }

  void _toast(Emitter<TunnelState> emit, String text, {bool error = false}) {
    _messageId += 1;
    emit(
      state.copyWith(
        message: HomeMessage(id: _messageId, text: text, error: error),
      ),
    );
  }

  @override
  Future<void> close() async {
    _cancelAwaitTimer();
    _cancelDisconnectTimer();
    _stopSessionPolling();
    await _tunnelStatesSub?.cancel();
    await _engineLogsSub?.cancel();
    await _proxyExposureSub?.cancel();
    return super.close();
  }
}
