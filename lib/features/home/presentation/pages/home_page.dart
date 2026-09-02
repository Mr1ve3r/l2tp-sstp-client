import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:get_it/get_it.dart';
import 'package:share_plus/share_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import 'package:tunnel_forge/app/ui/app_scaffold_messenger.dart';
import 'package:tunnel_forge/features/app_selector/presentation/pages/app_selector_page.dart';
import 'package:tunnel_forge/core/network/connectivity_checker.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/presentation/profile_picker_sheet.dart';
import 'package:tunnel_forge/features/profiles/data/profile_store.dart';
import 'package:tunnel_forge/features/profiles/domain/failover_group.dart';
import 'package:tunnel_forge/core/logging/log_entry.dart';
import 'package:tunnel_forge/core/vpn_protocol.dart';
import 'package:tunnel_forge/features/home/presentation/widgets/connection_panel.dart';
import 'package:tunnel_forge/features/home/presentation/widgets/logs_panel.dart';
import 'package:tunnel_forge/features/home/presentation/widgets/settings_panel.dart';
import 'package:tunnel_forge/features/trust/domain/trust_repository.dart';
import 'package:tunnel_forge/features/trust/presentation/bloc/certificates_bloc.dart';
import 'package:tunnel_forge/features/trust/presentation/pages/certificates_page.dart';
import '../../../app_theme/presentation/bloc/app_theme_bloc.dart';
import '../../../home/domain/home_models.dart';
import '../../../home/domain/home_repositories.dart';
import '../../../onboarding/presentation/bloc/onboarding_bloc.dart';
import '../../../onboarding/presentation/pages/onboarding_page.dart';
import '../bloc/connectivity_bloc.dart';
import '../bloc/home_nav_bloc.dart';
import '../bloc/logs_bloc.dart';
import '../bloc/profiles_bloc.dart';
import '../bloc/settings_bloc.dart';
import '../bloc/tunnel_bloc.dart';

class VpnHomePage extends StatelessWidget {
  const VpnHomePage({super.key, required this.locator});

  final GetIt locator;

  @override
  Widget build(BuildContext context) {
    return MultiBlocProvider(
      providers: [
        BlocProvider<HomeNavBloc>(create: (_) => locator<HomeNavBloc>()),
        BlocProvider<ProfilesBloc>(
          create: (_) => locator<ProfilesBloc>()..add(const ProfilesStarted()),
        ),
        BlocProvider<SettingsBloc>(
          create: (_) => locator<SettingsBloc>()..add(const SettingsStarted()),
        ),
        BlocProvider<LogsBloc>(
          create: (_) => locator<LogsBloc>()..add(const LogsStarted()),
        ),
        BlocProvider<TunnelBloc>(
          create: (_) => locator<TunnelBloc>()..add(const TunnelStarted()),
        ),
        BlocProvider<ConnectivityBloc>(
          create: (_) => locator<ConnectivityBloc>(),
        ),
      ],
      child: _VpnHomePageView(locator: locator),
    );
  }
}

class _VpnHomePageView extends StatefulWidget {
  const _VpnHomePageView({required this.locator});

  final GetIt locator;

  @override
  State<_VpnHomePageView> createState() => _VpnHomePageViewState();
}

class _VpnHomePageViewState extends State<_VpnHomePageView>
    with WidgetsBindingObserver {
  static const String _kGithubReleasesUrl =
      'https://github.com/Mr1ve3r/l2tp-sstp-client/releases';
  static const String _kProjectGithubUrl =
      'https://github.com/Mr1ve3r/l2tp-sstp-client';
  static const String _kTelegramUrl = 'https://t.me/TunnelForge';

  final ScrollController _logsScroll = ScrollController();
  bool _logsStickToBottom = true;
  int _lastProfilesMessageId = 0;
  int _lastTunnelMessageId = 0;
  int _lastSettingsMessageId = 0;
  bool _lastTunnelUp = false;
  int _lastLogsEntryCount = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _logsScroll.addListener(_syncLogsStickToBottom);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _logsScroll.removeListener(_syncLogsStickToBottom);
    _logsScroll.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed || !mounted) return;
    context.read<SettingsBloc>().add(
      const SettingsBatteryOptimizationRefreshRequested(),
    );
  }

  void _syncLogsStickToBottom() {
    if (!_logsScroll.hasClients) return;
    final metrics = _logsScroll.position;
    const edge = 88.0;
    final atBottom = metrics.pixels >= metrics.maxScrollExtent - edge;
    if (atBottom == _logsStickToBottom) return;
    setState(() => _logsStickToBottom = atBottom);
  }

  void _scheduleScrollLogsToEnd() {
    if (!_logsStickToBottom) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_logsScroll.hasClients) return;
      _logsScroll.position.jumpTo(_logsScroll.position.maxScrollExtent);
    });
  }

  void _jumpLogsToBottom() {
    setState(() => _logsStickToBottom = true);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_logsScroll.hasClients) return;
      _logsScroll.animateTo(
        _logsScroll.position.maxScrollExtent,
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOutCubic,
      );
    });
  }

  void _toast(String text, {bool error = false}) {
    showAppSnackBar(context, text, error: error);
  }

  Future<void> _handleProfilesStateChange(ProfilesState state) async {
    final message = state.message;
    if (message != null && message.id != _lastProfilesMessageId) {
      _lastProfilesMessageId = message.id;
      _toast(message.text, error: message.error);
    }
  }

  ConnectivityPingRequest _connectivityPingRequest(
    SettingsState settingsState,
    TunnelState tunnelState,
  ) {
    final connectivitySettings = settingsState.connectivityCheckSettings;
    final url = connectivitySettings.url;
    final timeoutMs = connectivitySettings.timeoutMs;
    if (settingsState.connectionMode == ConnectionMode.proxyOnly) {
      final proxyPort = tunnelState.proxyExposure?.httpPort;
      return ConnectivityPingRequest.localHttpProxy(
        url: url,
        timeoutMs: timeoutMs,
        proxyPort: ProxySettings.normalizePort(
          proxyPort ?? settingsState.proxySettings.httpPort,
          fallback: ProxySettings.defaultHttpPort,
        ),
      );
    }
    return ConnectivityPingRequest.directWithTimeout(
      url: url,
      timeoutMs: timeoutMs,
    );
  }

  Future<void> _handleTunnelStateChange(TunnelState current) async {
    final message = current.message;
    if (message != null && message.id != _lastTunnelMessageId) {
      _lastTunnelMessageId = message.id;
      _toast(message.text, error: message.error);
    }
    final settingsState = context.read<SettingsBloc>().state;
    if (!_lastTunnelUp && current.tunnelUp && !current.stopRequested) {
      context.read<ConnectivityBloc>().add(
        ConnectivityRunRequested(
          _connectivityPingRequest(settingsState, current),
        ),
      );
    } else if (_lastTunnelUp && !current.tunnelUp) {
      context.read<ConnectivityBloc>().add(const ConnectivityResetRequested());
    }
    context.read<ProfilesBloc>().add(
      ProfilesImportSelectionPolicyChanged(
        !current.busy &&
            !current.stopRequested &&
            !current.tunnelUp &&
            !current.awaitingTunnel,
      ),
    );
    _lastTunnelUp = current.tunnelUp;
  }

  Future<void> _handleLogsStateChange(LogsState current) async {
    if (_lastLogsEntryCount != current.entries.length) {
      _lastLogsEntryCount = current.entries.length;
      _scheduleScrollLogsToEnd();
    }
  }

  Future<void> _openProfilePicker() async {
    final profilesBloc = context.read<ProfilesBloc>();
    final profilesState = profilesBloc.state;
    if (profilesState.loading || !mounted) return;
    await ProfilePickerSheet.show(
      context,
      profilesBloc: profilesBloc,
      store: widget.locator<ProfileStore>(),
      certificates: widget.locator<CertificatesRepository>(),
    );
  }

  Future<void> _pickAppsForVpn() async {
    final t = AppText.current;
    final splitTunnelSettings = context
        .read<SettingsBloc>()
        .state
        .splitTunnelSettings;
    final isInclusive = splitTunnelSettings.mode == SplitTunnelMode.inclusive;
    final current = splitTunnelSettings.activePackages;
    final repository = widget.locator<TunnelRepository>();
    final picked = await Navigator.of(context, rootNavigator: true)
        .push<Set<String>>(
          MaterialPageRoute(
            fullscreenDialog: true,
            builder: (ctx) => AppSelectorPage(
              title: isInclusive ? t.appsUsingVpn : t.appsOutsideVpn,
              description: isInclusive
                  ? t.onlySelectedAppsVpn
                  : t.selectedAppsBypass,
              initialSelection: Set<String>.from(current),
              loadApps: repository.listVpnCandidateApps,
              loadIcon: repository.getAppIcon,
            ),
          ),
        );
    if (!mounted || picked == null) return;
    final nextSettings = isInclusive
        ? splitTunnelSettings.copyWith(inclusivePackages: picked.toList())
        : splitTunnelSettings.copyWith(exclusivePackages: picked.toList());
    context.read<SettingsBloc>().add(
      SettingsSplitTunnelSettingsChanged(nextSettings),
    );
  }

  /// Opens the server certificate store (SPEC 5.9).
  Future<void> _openServerCertificates() async {
    await Navigator.of(context, rootNavigator: true).push(
      MaterialPageRoute<void>(
        builder: (_) => BlocProvider<CertificatesBloc>(
          create: (_) =>
              widget.locator<CertificatesBloc>()
                ..add(const CertificatesStarted()),
          child: const CertificatesPage(),
        ),
      ),
    );
  }

  Future<void> _openL2tpSecurityNotice() async {
    await Navigator.of(context, rootNavigator: true).push(
      MaterialPageRoute<void>(
        fullscreenDialog: true,
        builder: (_) => BlocProvider<OnboardingBloc>(
          create: (_) =>
              widget.locator<OnboardingBloc>()
                ..add(const OnboardingReadOnlyOpened()),
          child: const OnboardingPage(),
        ),
      ),
    );
  }

  Future<void> _openExternalUrl(
    String url, {
    required String invalidMessage,
    required String failureMessage,
  }) async {
    final uri = Uri.tryParse(url);
    if (uri == null) {
      _toast(invalidMessage, error: true);
      return;
    }
    final launched = await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (!launched) {
      _toast(failureMessage, error: true);
    }
  }

  Future<void> _openReleasePage(String url) {
    return _openExternalUrl(
      url,
      invalidMessage: AppText.current.releasePageUrlInvalid,
      failureMessage: AppText.current.couldNotOpenReleasePage,
    );
  }

  void _handleMissingProfileTap(ProfilesState profilesState) {
    // An empty group is its own case: something *is* chosen, so telling the
    // user to choose something would be wrong advice.
    final emptyGroup =
        profilesState.activeGroup != null && !profilesState.hasActiveGroup;
    final message = emptyGroup
        ? AppText.current.failoverGroupHasNoProfiles
        : (profilesState.profiles.isEmpty
              ? AppText.current.createProfileFirst
              : (profilesState.groups.isEmpty
                    ? AppText.current.chooseSavedProfileBeforeConnecting
                    : AppText
                          .current
                          .chooseSavedProfileOrGroupBeforeConnecting));
    _toast(message, error: true);
    widget.locator<LogsRepository>().append(
      LogEntry(
        timestamp: DateTime.now(),
        level: LogLevel.warning,
        source: LogSource.dart,
        tag: 'tunnel',
        message: 'Connect blocked: no active profile',
      ),
    );
  }

  void _primaryAction(
    ProfilesState profilesState,
    SettingsState settingsState,
    TunnelState tunnelState,
  ) {
    if (tunnelState.busy || tunnelState.stopRequested) {
      return;
    }
    if (tunnelState.tunnelUp ||
        (tunnelState.awaitingTunnel && !tunnelState.tunnelUp)) {
      context.read<TunnelBloc>().add(const TunnelDisconnectRequested());
      return;
    }
    final group = profilesState.activeGroup;
    if (group != null) {
      _startFailoverGroup(group, profilesState, settingsState);
      return;
    }
    final row = profilesState.activeProfileRow;
    if (row == null || !profilesState.hasActiveProfile) {
      _handleMissingProfileTap(profilesState);
      return;
    }
    final profile = row.profile;
    if (settingsState.connectionMode == ConnectionMode.vpnTunnel) {
      context.read<SettingsBloc>().add(
        const SettingsBatteryOptimizationVpnConnectAttempted(),
      );
    }
    final trimmedName = profile.displayName.trim();
    final dnsServers = profile.dnsAutomatic
        ? const <DnsServerConfig>[]
        : Profile.orderedDnsServers(
            dns1Host: profile.dns1Host,
            dns1Protocol: profile.dns1Protocol,
            dns2Host: profile.dns2Host,
            dns2Protocol: profile.dns2Protocol,
          );
    context.read<TunnelBloc>().add(
      TunnelConnectRequested(
        TunnelConnectRequest(
          activeProfileId: profile.id,
          profileName: trimmedName.isEmpty ? null : trimmedName,
          server: profile.server,
          protocol: profile.protocol,
          sstp: SstpConnectSettings.fromProfile(
            profile,
            proxyPassword: row.proxyPassword,
          ),
          user: profile.user,
          password: row.password,
          psk: row.psk,
          dnsAutomatic: profile.dnsAutomatic,
          dnsServers: dnsServers,
          mtu: profile.mtu,
          connectionMode: settingsState.connectionMode,
          splitTunnelSettings: settingsState.splitTunnelSettings,
          proxySettings: settingsState.proxySettings,
        ),
      ),
    );
  }

  /// Starts a failover group (SPEC 10.1).
  ///
  /// Nothing about the members is read here. They are whole profiles the host
  /// resolves for itself as it walks the list, so passing a snapshot of them
  /// from this side would only be a second copy that could already be stale.
  void _startFailoverGroup(
    FailoverGroup group,
    ProfilesState profilesState,
    SettingsState settingsState,
  ) {
    if (!profilesState.hasActiveGroup) {
      _handleMissingProfileTap(profilesState);
      return;
    }
    if (settingsState.connectionMode == ConnectionMode.vpnTunnel) {
      context.read<SettingsBloc>().add(
        const SettingsBatteryOptimizationVpnConnectAttempted(),
      );
    }
    context.read<TunnelBloc>().add(
      TunnelConnectGroupRequested(
        TunnelGroupConnectRequest(
          groupId: group.id,
          groupName: group.displayName,
          memberCount: profilesState.activeGroupMembers.length,
          connectTimeoutSec: group.connectTimeoutSec,
          connectionMode: settingsState.connectionMode,
          proxySettings: settingsState.proxySettings,
        ),
      ),
    );
  }

  Future<void> _copyLogs(LogsState logsState) async {
    final visibleLogs = logsState.visibleLogs;
    if (visibleLogs.isEmpty) {
      _toast(
        logsState.entries.isEmpty
            ? AppText.current.noLogsToCopy
            : AppText.current.noVisibleLogsToCopy,
      );
      return;
    }
    await Clipboard.setData(
      ClipboardData(
        text: visibleLogs.map((entry) => entry.toPlainText()).join('\n'),
      ),
    );
    final count = visibleLogs.length;
    _toast(AppText.current.copiedLinesToClipboard(count));
  }

  Future<void> _shareDebugLogs(LogsState logsState) async {
    // Debug display level includes all severities, so export all buffered logs.
    final debugLevelLogs = logsState.entries;
    if (debugLevelLogs.isEmpty) {
      _toast(AppText.current.noDebugLogsToShare);
      return;
    }
    final content = debugLevelLogs
        .map((entry) => entry.toPlainText())
        .join('\n');
    final bytes = Uint8List.fromList(utf8.encode(content));
    final now = DateTime.now();
    final month = now.month.toString().padLeft(2, '0');
    final day = now.day.toString().padLeft(2, '0');
    final hour = now.hour.toString().padLeft(2, '0');
    final minute = now.minute.toString().padLeft(2, '0');
    final second = now.second.toString().padLeft(2, '0');
    final fileName =
        'tunnel_forge_debug_logs_${now.year}$month${day}_$hour$minute$second.txt';
    try {
      await SharePlus.instance.share(
        ShareParams(
          files: [XFile.fromData(bytes, mimeType: 'text/plain')],
          fileNameOverrides: [fileName],
          title: 'Share TunnelForge debug logs',
        ),
      );
      final count = debugLevelLogs.length;
      _toast(AppText.current.preparedDebugLinesForSharing(count));
    } catch (_) {
      _toast(AppText.current.couldNotShareDebugLogs, error: true);
    }
  }

  void _clearLogs() {
    context.read<LogsBloc>().add(const LogsCleared());
    setState(() => _logsStickToBottom = true);
    _toast(AppText.current.logsCleared);
  }

  String _connectButtonLabel(
    TunnelState tunnelState,
    bool hasActiveProfile,
    ConnectionMode connectionMode,
  ) {
    final t = AppText.current;
    if (tunnelState.stopRequested && tunnelState.tunnelUp) {
      return t.disconnecting;
    }
    if (tunnelState.stopRequested) return t.canceling;
    if (tunnelState.busy && tunnelState.tunnelUp) return t.disconnecting;
    if (tunnelState.awaitingTunnel && !tunnelState.tunnelUp) {
      return t.connectingTapCancel;
    }
    if (tunnelState.busy) return t.working;
    if (tunnelState.tunnelUp) {
      return connectionMode == ConnectionMode.proxyOnly
          ? t.proxyReady
          : t.connected;
    }
    if (!hasActiveProfile) return t.selectProfile;
    return t.ready;
  }

  String _connectivityBadgeLabel(ConnectivityState state) {
    final t = AppText.current;
    return switch (state.badgeState) {
      ConnectivityBadgeState.idle => t.tapToCheck,
      ConnectivityBadgeState.checking => t.checking,
      ConnectivityBadgeState.success => '${state.latencyMs ?? 0} ms',
      ConnectivityBadgeState.failure => t.unreachable,
    };
  }

  @override
  Widget build(BuildContext context) {
    final navState = context.watch<HomeNavBloc>().state;
    final profilesState = context.watch<ProfilesBloc>().state;
    final settingsState = context.watch<SettingsBloc>().state;
    final tunnelState = context.watch<TunnelBloc>().state;
    final logsState = context.watch<LogsBloc>().state;
    final connectivityState = context.watch<ConnectivityBloc>().state;
    final appThemeState = context.watch<AppThemeBloc>().state;
    final t = AppLocalizations.of(context);
    final hasDebugLogs = logsState.entries.isNotEmpty;
    final languageController = AppLanguageController.of(context);

    final effectiveProfileId = profilesState.hasActiveProfile
        ? profilesState.activeProfileId
        : null;
    Profile? activeProfile;
    if (effectiveProfileId != null) {
      for (final profile in profilesState.profiles) {
        if (profile.id == effectiveProfileId) {
          activeProfile = profile;
          break;
        }
      }
    }
    // A failover group can be what the button starts instead of a profile
    // (SPEC 10.1.3). The tile shows what it will try and in what order, which
    // is the part the status line cannot say until the walk has begun.
    final activeGroup = profilesState.activeGroup;
    final groupMemberLine = profilesState.activeGroupMembers
        .map((profile) => '${profile.displayName} (${profile.protocol.label})')
        .join('  →  ');
    final profileSummaryTitle = activeGroup != null
        ? activeGroup.displayName
        : (activeProfile != null
              ? activeProfile.displayName
              : (profilesState.profiles.isEmpty
                    ? t.noSavedProfile
                    : t.quickConnect));
    // While a group is walking its list the host says which member it is on;
    // that replaces the static order, because it is the one thing the user
    // cannot work out for themselves (SPEC 10.1.3).
    final walkingDetail = tunnelState.awaitingTunnel && !tunnelState.tunnelUp
        ? tunnelState.connectingDetail
        : null;
    final profileSummarySubtitle = activeGroup != null
        ? (walkingDetail ??
              (groupMemberLine.isEmpty
                  ? t.failoverGroupIsEmpty
                  : groupMemberLine))
        : (activeProfile != null
              ? activeProfile.server
              : (profilesState.profiles.isEmpty
                    ? t.createFirstProfile
                    : t.selectSavedProfile));
    final appBarTitle = switch (navState.index) {
      0 => t.appTitle,
      1 => t.logs,
      _ => t.settings,
    };

    return MultiBlocListener(
      listeners: [
        BlocListener<ProfilesBloc, ProfilesState>(
          listenWhen: (previous, current) =>
              previous.message != current.message,
          listener: (context, state) => _handleProfilesStateChange(state),
        ),
        BlocListener<TunnelBloc, TunnelState>(
          listenWhen: (previous, current) =>
              previous.message != current.message ||
              previous.tunnelUp != current.tunnelUp ||
              previous.busy != current.busy ||
              previous.awaitingTunnel != current.awaitingTunnel ||
              previous.stopRequested != current.stopRequested,
          listener: (context, state) => _handleTunnelStateChange(state),
        ),
        BlocListener<LogsBloc, LogsState>(
          listenWhen: (previous, current) =>
              previous.entries.length != current.entries.length,
          listener: (context, state) => _handleLogsStateChange(state),
        ),
        BlocListener<SettingsBloc, SettingsState>(
          listenWhen: (previous, current) =>
              previous.message != current.message,
          listener: (context, state) {
            final message = state.message;
            if (message != null && message.id != _lastSettingsMessageId) {
              _lastSettingsMessageId = message.id;
              _toast(message.text, error: message.error);
            }
          },
        ),
      ],
      child: Scaffold(
        appBar: AppBar(
          title: AnimatedSwitcher(
            duration: const Duration(milliseconds: 360),
            switchInCurve: Curves.easeOutCubic,
            switchOutCurve: Curves.easeInCubic,
            transitionBuilder: (child, animation) {
              final curved = CurvedAnimation(
                parent: animation,
                curve: Curves.easeOutCubic,
              );
              return FadeTransition(
                opacity: curved,
                child: SlideTransition(
                  position: Tween<Offset>(
                    begin: const Offset(0, -0.18),
                    end: Offset.zero,
                  ).animate(curved),
                  child: ScaleTransition(
                    scale: Tween<double>(begin: 0.98, end: 1).animate(curved),
                    child: child,
                  ),
                ),
              );
            },
            child: Text(appBarTitle, key: ValueKey<String>(appBarTitle)),
          ),
          actions: [
            if (navState.index == 1)
              Padding(
                padding: const EdgeInsets.only(right: 4),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    PopupMenuButton<VpnProtocolFilter>(
                      tooltip: 'Protocol',
                      initialValue: logsState.protocolFilter,
                      onSelected: (filter) => context.read<LogsBloc>().add(
                        LogsProtocolFilterChangeRequested(filter),
                      ),
                      itemBuilder: (context) => const [
                        PopupMenuItem<VpnProtocolFilter>(
                          value: VpnProtocolFilter.all,
                          child: Text('ALL'),
                        ),
                        PopupMenuItem<VpnProtocolFilter>(
                          value: VpnProtocolFilter.l2tp,
                          child: Text('L2TP'),
                        ),
                        PopupMenuItem<VpnProtocolFilter>(
                          value: VpnProtocolFilter.sstp,
                          child: Text('SSTP'),
                        ),
                      ],
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 8,
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Icon(Icons.vpn_lock_rounded, size: 20),
                            const SizedBox(width: 6),
                            Text(
                              logsState.protocolLabel,
                              style: Theme.of(context).textTheme.labelMedium
                                  ?.copyWith(
                                    color: Theme.of(
                                      context,
                                    ).colorScheme.onSurfaceVariant,
                                  ),
                            ),
                          ],
                        ),
                      ),
                    ),
                    PopupMenuButton<LogDisplayLevel>(
                      tooltip: t.logLevel,
                      initialValue: logsState.level,
                      onSelected: (level) => context.read<LogsBloc>().add(
                        LogsLevelChangeRequested(level),
                      ),
                      itemBuilder: (context) => const [
                        PopupMenuItem<LogDisplayLevel>(
                          value: LogDisplayLevel.info,
                          child: Text('INFO'),
                        ),
                        PopupMenuItem<LogDisplayLevel>(
                          value: LogDisplayLevel.warning,
                          child: Text('WARNING'),
                        ),
                        PopupMenuItem<LogDisplayLevel>(
                          value: LogDisplayLevel.error,
                          child: Text('ERROR'),
                        ),
                        PopupMenuItem<LogDisplayLevel>(
                          value: LogDisplayLevel.debug,
                          child: Text('DEBUG'),
                        ),
                      ],
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 8,
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Icon(Icons.filter_list_rounded, size: 20),
                            const SizedBox(width: 6),
                            Text(
                              logsState.levelLabel,
                              style: Theme.of(context).textTheme.labelMedium
                                  ?.copyWith(
                                    color: Theme.of(
                                      context,
                                    ).colorScheme.onSurfaceVariant,
                                  ),
                            ),
                          ],
                        ),
                      ),
                    ),
                    IconButton(
                      tooltip: logsState.wordWrap
                          ? t.wordWrapOff
                          : t.wordWrapOn,
                      onPressed: () => context.read<LogsBloc>().add(
                        const LogsWordWrapToggled(),
                      ),
                      icon: Icon(
                        logsState.wordWrap ? Icons.wrap_text : Icons.swap_horiz,
                      ),
                    ),
                    IconButton(
                      tooltip: t.copyVisible,
                      onPressed: logsState.visibleLogs.isEmpty
                          ? null
                          : () => _copyLogs(logsState),
                      icon: const Icon(Icons.copy_all_outlined),
                    ),
                    IconButton(
                      tooltip: t.shareDebugLogs,
                      onPressed: hasDebugLogs
                          ? () => _shareDebugLogs(logsState)
                          : null,
                      icon: const Icon(Icons.ios_share_rounded),
                    ),
                    IconButton(
                      tooltip: t.clear,
                      onPressed: logsState.entries.isEmpty ? null : _clearLogs,
                      icon: const Icon(Icons.delete_outline),
                    ),
                  ],
                ),
              ),
          ],
        ),
        body: Padding(
          padding: const EdgeInsets.only(top: 12),
          child: AnimatedSwitcher(
            duration: const Duration(milliseconds: 420),
            switchInCurve: Curves.easeOutCubic,
            switchOutCurve: Curves.easeInCubic,
            layoutBuilder: (currentChild, previousChildren) => Stack(
              fit: StackFit.expand,
              alignment: Alignment.topCenter,
              children: <Widget>[?currentChild, ...previousChildren],
            ),
            transitionBuilder: (child, animation) {
              final curved = CurvedAnimation(
                parent: animation,
                curve: Curves.easeOutCubic,
              );
              return FadeTransition(
                opacity: curved,
                child: ScaleTransition(
                  scale: Tween<double>(begin: 0.975, end: 1).animate(curved),
                  child: child,
                ),
              );
            },
            child: SizedBox.expand(
              key: ValueKey<int>(navState.index),
              child: ColoredBox(
                color: Theme.of(context).colorScheme.surface,
                child: switch (navState.index) {
                  0 => Align(
                    alignment: Alignment.topCenter,
                    child: ConnectionPanel(
                      profilesLoading: profilesState.loading,
                      profileSummaryTitle: profileSummaryTitle,
                      profileSummarySubtitle: profileSummarySubtitle,
                      onOpenProfilePicker: _openProfilePicker,
                      busy: tunnelState.busy,
                      tunnelUp: tunnelState.tunnelUp,
                      awaitingTunnel: tunnelState.awaitingTunnel,
                      stopRequested: tunnelState.stopRequested,
                      canStartConnection:
                          profilesState.hasActiveProfile ||
                          profilesState.hasActiveGroup,
                      connectButtonLabel: _connectButtonLabel(
                        tunnelState,
                        profilesState.hasActiveProfile ||
                            profilesState.hasActiveGroup,
                        settingsState.connectionMode,
                      ),
                      onPrimary: () => _primaryAction(
                        profilesState,
                        settingsState,
                        tunnelState,
                      ),
                      onUnavailablePrimaryTap: () =>
                          _handleMissingProfileTap(profilesState),
                      session: tunnelState.session,
                      connectivityBadgeState: connectivityState.badgeState,
                      connectivityBadgeLabel: _connectivityBadgeLabel(
                        connectivityState,
                      ),
                      onConnectivityTap: () =>
                          context.read<ConnectivityBloc>().add(
                            ConnectivityRunRequested(
                              _connectivityPingRequest(
                                settingsState,
                                tunnelState,
                              ),
                            ),
                          ),
                      colorScheme: Theme.of(context).colorScheme,
                      textTheme: Theme.of(context).textTheme,
                    ),
                  ),
                  1 => LogsPanel(
                    logs: logsState.visibleLogs,
                    scrollController: _logsScroll,
                    colorScheme: Theme.of(context).colorScheme,
                    textTheme: Theme.of(context).textTheme,
                    stickToBottom: _logsStickToBottom,
                    onJumpToLatest: _jumpLogsToBottom,
                    wordWrap: logsState.wordWrap,
                    hasAnyLogs: logsState.entries.isNotEmpty,
                  ),
                  _ => SettingsPanel(
                    language: languageController.language,
                    onLanguageChanged: languageController.setLanguage,
                    themeMode: appThemeState.themeMode,
                    onThemeModeChanged: (mode) =>
                        context.read<AppThemeBloc>().add(AppThemeChanged(mode)),
                    connectionMode: settingsState.connectionMode,
                    splitTunnelSettings: settingsState.splitTunnelSettings,
                    proxySettings: settingsState.proxySettings,
                    connectivityCheckSettings:
                        settingsState.connectivityCheckSettings,
                    onConnectionModeChanged: (mode) => context
                        .read<SettingsBloc>()
                        .add(SettingsConnectionModeChanged(mode)),
                    onSplitTunnelSettingsChanged: (settings) => context
                        .read<SettingsBloc>()
                        .add(SettingsSplitTunnelSettingsChanged(settings)),
                    onProxySettingsChanged: (settings) => context
                        .read<SettingsBloc>()
                        .add(SettingsProxySettingsChanged(settings)),
                    proxyExposure: tunnelState.proxyExposure,
                    onConnectivityCheckSettingsChanged: (settings) =>
                        context.read<SettingsBloc>().add(
                          SettingsConnectivityCheckSettingsChanged(settings),
                        ),
                    batteryOptimizationStatus:
                        settingsState.batteryOptimizationStatus,
                    batteryOptimizationBusy:
                        settingsState.batteryOptimizationBusy,
                    onRefreshBatteryOptimization: () =>
                        context.read<SettingsBloc>().add(
                          const SettingsBatteryOptimizationRefreshRequested(),
                        ),
                    onRequestBatteryOptimization: () => context
                        .read<SettingsBloc>()
                        .add(const SettingsBatteryOptimizationRequestPressed()),
                    onChooseApps: _pickAppsForVpn,
                    onOpenL2tpSecurityNotice: _openL2tpSecurityNotice,
                    onOpenServerCertificates: _openServerCertificates,
                    installedVersion: settingsState.installedVersion,
                    installedVersionError: settingsState.installedVersionError,
                    updateCheckConsentGranted:
                        settingsState.updateCheckConsentGranted,
                    appUpdateStatus: settingsState.appUpdateStatus,
                    latestReleaseVersion: settingsState.latestReleaseVersion,
                    updateErrorMessage: settingsState.updateErrorMessage,
                    onRefreshVersionCheck: () =>
                        context.read<SettingsBloc>().add(
                          settingsState.updateCheckConsentGranted
                              ? const SettingsVersionCheckRequested()
                              : const SettingsVersionCheckConsentGranted(),
                        ),
                    onOpenReleasePage: () => _openReleasePage(
                      settingsState.latestReleaseUrl ?? _kGithubReleasesUrl,
                    ),
                    onOpenTelegram: () => _openExternalUrl(
                      _kTelegramUrl,
                      invalidMessage: AppText.current.telegramLinkInvalid,
                      failureMessage: AppText.current.couldNotOpenTelegram,
                    ),
                    onOpenGithub: () => _openExternalUrl(
                      _kProjectGithubUrl,
                      invalidMessage: AppText.current.githubLinkInvalid,
                      failureMessage: AppText.current.couldNotOpenGithub,
                    ),
                    routingLocked:
                        profilesState.loading ||
                        tunnelState.busy ||
                        tunnelState.stopRequested ||
                        tunnelState.tunnelUp ||
                        tunnelState.awaitingTunnel,
                    colorScheme: Theme.of(context).colorScheme,
                    textTheme: Theme.of(context).textTheme,
                  ),
                },
              ),
            ),
          ),
        ),
        bottomNavigationBar: NavigationBar(
          selectedIndex: navState.index,
          onDestinationSelected: (index) {
            context.read<HomeNavBloc>().add(HomeNavChanged(index));
            if (index == 1) _scheduleScrollLogsToEnd();
          },
          backgroundColor: Theme.of(context).colorScheme.surface,
          surfaceTintColor: Colors.transparent,
          indicatorColor: Theme.of(context).colorScheme.secondaryContainer,
          height: 72,
          labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
          animationDuration: const Duration(milliseconds: 360),
          destinations: [
            NavigationDestination(
              icon: const Icon(Icons.vpn_key_outlined),
              selectedIcon: const Icon(Icons.vpn_key),
              label: t.vpn,
            ),
            NavigationDestination(
              icon: const Icon(Icons.article_outlined),
              selectedIcon: const Icon(Icons.article),
              label: t.logs,
            ),
            NavigationDestination(
              icon: const Icon(Icons.settings_outlined),
              selectedIcon: const Icon(Icons.settings),
              label: t.settings,
            ),
          ],
        ),
      ),
    );
  }
}
