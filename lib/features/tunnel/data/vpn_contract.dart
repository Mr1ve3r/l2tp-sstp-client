/// Method-channel API contract: names and argument keys for the Android VPN bridge.
///
/// Dart calls [prepareVpn], [connect], and [disconnect]. The service may invoke
/// [onTunnelState] and [onEngineLog] on the same channel. Native code must use these exact strings.
abstract final class VpnContract {
  static const String channel = 'io.github.evokelektrique.tunnelforge/vpn';

  static const String prepareVpn = 'prepareVpn';
  static const String connect = 'connect';

  /// Connects a failover group by id, rather than one profile (SPEC 10.1).
  static const String connectGroup = 'connectGroup';
  static const String argGroupId = 'groupId';
  static const String disconnect = 'disconnect';
  static const String setLogLevel = 'setLogLevel';
  static const String getRuntimeState = 'getRuntimeState';
  static const String getBatteryOptimizationStatus =
      'getBatteryOptimizationStatus';
  static const String requestIgnoreBatteryOptimizations =
      'requestIgnoreBatteryOptimizations';
  static const String openBatteryOptimizationSettings =
      'openBatteryOptimizationSettings';
  static const String openManufacturerBackgroundSettings =
      'openManufacturerBackgroundSettings';

  /// Correlates one connect attempt across Dart UI, logs, and Android logcat.
  static const String argAttemptId = 'attemptId';
  static const String argLogLevel = 'logLevel';
  static const String argBatteryOptimizationState = 'state';
  static const String argBatteryOptimizationOutcome = 'outcome';
  static const String argBatteryOptimizationMessage = 'message';
  static const String argBatteryOptimizationPowerSaveMode = 'powerSaveMode';
  static const String argBatteryOptimizationManufacturer = 'manufacturer';
  static const String argBatteryOptimizationAndroidSdkInt = 'androidSdkInt';

  static const String argServer = 'server';
  static const String argUser = 'user';
  static const String argPassword = 'password';
  static const String argPsk = 'psk';
  static const String argDnsAutomatic = 'dnsAutomatic';
  static const String argDnsServers = 'dnsServers';
  static const String argDnsServerHost = 'host';
  static const String argDnsServerProtocol = 'protocol';
  static const String argMtu = 'mtu';

  /// `l2tp` or `sstp`; absent means L2TP (SPEC 7.1.1).
  static const String argProtocol = 'protocol';

  // The SSTP fields that travel with `argProtocol: 'sstp'`; the host ignores
  // them for any other protocol (SPEC 7.1.1).
  static const String argSstpPort = 'sstpPort';
  static const String argSstpTrustPolicy = 'sstpTrustPolicy';
  static const String argSstpCertificateIds = 'sstpCertificateIds';
  static const String argSstpPinnedFingerprints = 'sstpPinnedFingerprints';
  static const String argSstpExpectedHostname = 'sstpExpectedHostname';
  static const String argSstpMinTlsVersion = 'sstpMinTlsVersion';
  static const String argSstpAuthMethods = 'sstpAuthMethods';
  static const String argSstpProxyHost = 'sstpProxyHost';
  static const String argSstpProxyPort = 'sstpProxyPort';
  static const String argSstpProxyUsername = 'sstpProxyUsername';
  static const String argSstpProxyPassword = 'sstpProxyPassword';

  static const String argProfileName = 'profileName';
  static const String argConnectionMode = 'connectionMode';
  static const String argProxyHttpPort = 'proxyHttpPort';
  static const String argProxySocksPort = 'proxySocksPort';
  static const String argProxyAllowLan = 'proxyAllowLan';
  static const String argSplitTunnelEnabled = 'splitTunnelEnabled';
  static const String argSplitTunnelMode = 'splitTunnelMode';
  static const String argSplitTunnelInclusivePackages =
      'splitTunnelInclusivePackages';
  static const String argSplitTunnelExclusivePackages =
      'splitTunnelExclusivePackages';

  /// Android: launcher-visible apps as `{packageName, label}` maps.
  static const String listVpnCandidateApps = 'listVpnCandidateApps';

  /// Android: argument is package name; response is launcher icon PNG bytes or null.
  static const String getAppIcon = 'getAppIcon';

  static const String modeVpnTunnel = 'vpnTunnel';
  static const String modeProxyOnly = 'proxyOnly';
  static const String splitTunnelModeInclusive = 'inclusive';
  static const String splitTunnelModeExclusive = 'exclusive';

  static const String onTunnelState = 'onTunnelState';

  static const String argTunnelState = 'tunnelState';
  static const String argTunnelDetail = 'tunnelDetail';

  /// `l2tp`, `sstp`, or absent when no session is running (SPEC 9.1.9).
  static const String argTunnelProtocol = 'tunnelProtocol';

  /// `EngineError.messageKey` of a failure, phrased for the user on this side.
  static const String argTunnelErrorKey = 'tunnelErrorKey';

  // What the running session negotiated (SPEC 9.1.7).
  static const String argSessionAddress = 'sessionAddress';
  static const String argSessionDns = 'sessionDns';
  static const String argSessionMtu = 'sessionMtu';
  static const String argSessionSince = 'sessionSince';
  static const String argSessionRxBytes = 'sessionRxBytes';
  static const String argSessionTxBytes = 'sessionTxBytes';
  static const String argSessionProxyHost = 'sessionProxyHost';

  /// Android -> Dart: engine log lines; [argEngineLogLevel] uses `android.util.Log` priorities.
  static const String onEngineLog = 'onEngineLog';

  static const String argEngineLogLevel = 'engineLogLevel';
  static const String argEngineLogSource = 'engineLogSource';
  static const String argEngineLogTag = 'engineLogTag';
  static const String argEngineLogMessage = 'engineLogMessage';

  /// Which engine produced the line, or absent for a host line outside a session.
  static const String argEngineLogProtocol = 'engineLogProtocol';

  /// Android -> Dart: active local-proxy listener address/port exposure.
  static const String onProxyExposureChanged = 'onProxyExposureChanged';

  static const String argProxyExposureActive = 'proxyExposureActive';
  static const String argProxyExposureBindAddress = 'proxyExposureBindAddress';
  static const String argProxyExposureDisplayAddress =
      'proxyExposureDisplayAddress';
  static const String argProxyExposureHttpPort = 'proxyExposureHttpPort';
  static const String argProxyExposureSocksPort = 'proxyExposureSocksPort';
  static const String argProxyExposureLanRequested =
      'proxyExposureLanRequested';
  static const String argProxyExposureLanActive = 'proxyExposureLanActive';
  static const String argProxyExposureWarning = 'proxyExposureWarning';
}

/// Tunnel lifecycle strings sent with [VpnContract.onTunnelState] / [VpnContract.argTunnelState].
abstract final class VpnTunnelState {
  static const String connecting = 'connecting';
  static const String connected = 'connected';

  /// The network changed under a live tunnel and the host is rebuilding it.
  static const String reconnecting = 'reconnecting';
  static const String failed = 'failed';
  static const String stopped = 'stopped';
}
