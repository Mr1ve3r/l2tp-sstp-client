import 'package:equatable/equatable.dart';

import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/trust/domain/trust_models.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_transfer.dart';
import 'package:tunnel_forge/core/logging/log_entry.dart';
import 'package:tunnel_forge/core/vpn_protocol.dart';

class HomeMessage extends Equatable {
  const HomeMessage({required this.id, required this.text, this.error = false});

  final int id;
  final String text;
  final bool error;

  @override
  List<Object?> get props => [id, text, error];
}

class ProfileSecretRow extends Equatable {
  const ProfileSecretRow({
    required this.profile,
    required this.password,
    required this.psk,
    this.proxyPassword = '',
  });

  final Profile profile;
  final String password;
  final String psk;

  /// The HTTP proxy password of an SSTP profile (SPEC 9.1.3).
  final String proxyPassword;

  @override
  List<Object?> get props => [profile, password, psk, proxyPassword];
}

/// The certificate store as the profile editor needs it (SPEC 9.1.2).
class TrustOptions extends Equatable {
  const TrustOptions({
    this.certificates = const <ServerCertificate>[],
    this.policies = const <TrustPolicy>[],
  });

  final List<ServerCertificate> certificates;

  /// The policies this build offers; a release build omits INSECURE.
  final List<TrustPolicy> policies;

  @override
  List<Object?> get props => [certificates, policies];
}

class TunnelHostUpdate extends Equatable {
  const TunnelHostUpdate({
    required this.state,
    required this.detail,
    this.attemptId = '',
    this.errorKey,
  });

  final String state;
  final String detail;
  final String attemptId;

  /// `EngineError.messageKey` when the host reported a failure, so the message
  /// the user reads is phrased here rather than by the engine (SPEC 9.2).
  final String? errorKey;

  @override
  List<Object?> get props => [state, detail, attemptId, errorKey];
}

class EngineLogMessage extends Equatable {
  const EngineLogMessage({
    required this.timestamp,
    required this.level,
    required this.source,
    required this.tag,
    required this.message,
    this.protocol,
  });

  final DateTime timestamp;
  final LogLevel level;
  final LogSource source;
  final String tag;
  final String message;
  final VpnProtocol? protocol;

  LogEntry toLogEntry() {
    return LogEntry(
      timestamp: timestamp,
      level: level,
      source: source,
      tag: tag,
      message: message,
      protocol: protocol,
    );
  }

  @override
  List<Object?> get props => [timestamp, level, source, tag, message, protocol];
}

class TunnelConnectRequest extends Equatable {
  const TunnelConnectRequest({
    this.attemptId = '',
    required this.activeProfileId,
    required this.profileName,
    required this.server,
    required this.user,
    required this.password,
    required this.psk,
    required this.dnsAutomatic,
    required this.dnsServers,
    required this.mtu,
    required this.connectionMode,
    required this.splitTunnelSettings,
    required this.proxySettings,
    this.protocol = VpnProtocol.l2tp,
    this.sstp = const SstpConnectSettings(),
  });

  final String attemptId;
  final String? activeProfileId;
  final String? profileName;
  final String server;
  final String user;
  final String password;
  final String psk;
  final bool dnsAutomatic;
  final List<DnsServerConfig> dnsServers;
  final int mtu;
  final ConnectionMode connectionMode;
  final SplitTunnelSettings splitTunnelSettings;
  final ProxySettings proxySettings;

  /// What the host dispatches on (SPEC 7.1.1).
  final VpnProtocol protocol;

  /// Read only when [protocol] is [VpnProtocol.sstp].
  final SstpConnectSettings sstp;

  @override
  List<Object?> get props => [
    attemptId,
    activeProfileId,
    profileName,
    server,
    user,
    password,
    psk,
    dnsAutomatic,
    dnsServers,
    mtu,
    connectionMode,
    splitTunnelSettings,
    proxySettings,
    protocol,
    sstp,
  ];
}

/// A request to start a failover group rather than one profile (SPEC 10.1).
///
/// It carries almost nothing: the members are whole profiles in the host's
/// store, with their own servers, protocols and secrets, and the host reads
/// them itself. What is here is the group to run, the proxy listener settings
/// — which belong to this application and to no profile — and enough about the
/// group to say what is happening before the host answers.
class TunnelGroupConnectRequest extends Equatable {
  const TunnelGroupConnectRequest({
    this.attemptId = '',
    required this.groupId,
    required this.groupName,
    required this.memberCount,
    required this.connectTimeoutSec,
    required this.connectionMode,
    required this.proxySettings,
  });

  final String attemptId;
  final String groupId;
  final String groupName;

  /// How many members the group had when the button was pressed, and the budget
  /// each of them gets. Neither is sent to the host — it reads the group again
  /// and is the authority — but together they say how long the whole walk may
  /// take, which is how long this side is willing to wait for it.
  final int memberCount;
  final int connectTimeoutSec;

  /// What the user has the application set to. A group is always a VPN tunnel;
  /// this is here so that a group started in proxy-only mode is refused with a
  /// sentence rather than quietly changing the mode underneath the setting.
  final ConnectionMode connectionMode;

  final ProxySettings proxySettings;

  /// The longest a whole walk can honestly take: every member getting its full
  /// budget, one after another.
  Duration get worstCaseDuration =>
      Duration(seconds: memberCount * connectTimeoutSec);

  @override
  List<Object?> get props => [
    attemptId,
    groupId,
    groupName,
    memberCount,
    connectTimeoutSec,
    connectionMode,
    proxySettings,
  ];
}

class ImportTransferRequest extends Equatable {
  const ImportTransferRequest({
    required this.transfer,
    this.selectAsLastProfile = false,
  });

  final IncomingProfileTransfer transfer;
  final bool selectAsLastProfile;

  @override
  List<Object?> get props => [transfer, selectAsLastProfile];
}

enum AppUpdateStatus {
  idle,
  loading,
  upToDate,
  updateAvailable,
  aheadOfRelease,
  comparisonUnavailable,
  error,
}

class SemanticVersion extends Equatable implements Comparable<SemanticVersion> {
  const SemanticVersion({
    required this.major,
    required this.minor,
    required this.patch,
  });

  final int major;
  final int minor;
  final int patch;

  static final RegExp _pattern = RegExp(r'^v?(\d+)\.(\d+)\.(\d+)');

  static SemanticVersion? tryParse(String value) {
    final match = _pattern.firstMatch(value.trim());
    if (match == null) return null;
    return SemanticVersion(
      major: int.parse(match.group(1)!),
      minor: int.parse(match.group(2)!),
      patch: int.parse(match.group(3)!),
    );
  }

  @override
  int compareTo(SemanticVersion other) {
    final majorCompare = major.compareTo(other.major);
    if (majorCompare != 0) return majorCompare;
    final minorCompare = minor.compareTo(other.minor);
    if (minorCompare != 0) return minorCompare;
    return patch.compareTo(other.patch);
  }

  @override
  List<Object?> get props => [major, minor, patch];

  @override
  String toString() => '$major.$minor.$patch';
}

class AppVersionInfo extends Equatable {
  const AppVersionInfo({
    this.displayVersion,
    required this.semanticVersion,
    this.errorReason,
  });

  final String? displayVersion;
  final SemanticVersion? semanticVersion;
  final String? errorReason;

  bool get hasDisplayVersion =>
      displayVersion != null && displayVersion!.trim().isNotEmpty;

  @override
  List<Object?> get props => [displayVersion, semanticVersion, errorReason];
}

enum AppUpdateErrorKind { http, timeout, network, tls, response, unknown }

class AppUpdateException with EquatableMixin implements Exception {
  const AppUpdateException({
    required this.kind,
    required this.userMessage,
    this.statusCode,
    this.details,
  });

  final AppUpdateErrorKind kind;
  final String userMessage;
  final int? statusCode;
  final String? details;

  @override
  List<Object?> get props => [kind, userMessage, statusCode, details];
}

enum BatteryOptimizationState { unknown, unsupported, allowed, restricted }

class BatteryOptimizationStatus extends Equatable {
  const BatteryOptimizationStatus({
    required this.state,
    this.powerSaveMode = false,
    this.manufacturer,
    this.androidSdkInt,
  });

  const BatteryOptimizationStatus.unknown()
    : this(state: BatteryOptimizationState.unknown);

  final BatteryOptimizationState state;
  final bool powerSaveMode;
  final String? manufacturer;
  final int? androidSdkInt;

  bool get canRequestExemption => state == BatteryOptimizationState.restricted;

  static BatteryOptimizationStatus fromMap(Object? raw) {
    if (raw is! Map) return const BatteryOptimizationStatus.unknown();
    return BatteryOptimizationStatus(
      state: switch (raw['state']?.toString()) {
        'unsupported' => BatteryOptimizationState.unsupported,
        'allowed' => BatteryOptimizationState.allowed,
        'restricted' => BatteryOptimizationState.restricted,
        _ => BatteryOptimizationState.unknown,
      },
      powerSaveMode: raw['powerSaveMode'] == true,
      manufacturer: raw['manufacturer']?.toString(),
      androidSdkInt: switch (raw['androidSdkInt']) {
        int v => v,
        num v => v.toInt(),
        final v => int.tryParse(v?.toString() ?? ''),
      },
    );
  }

  @override
  List<Object?> get props => [
    state,
    powerSaveMode,
    manufacturer,
    androidSdkInt,
  ];
}

enum BatteryOptimizationRequestOutcome {
  unsupported,
  alreadyAllowed,
  requested,
  settingsOpened,
  failed,
}

class BatteryOptimizationRequestResult extends Equatable {
  const BatteryOptimizationRequestResult({required this.outcome, this.message});

  const BatteryOptimizationRequestResult.failed([String? message])
    : this(outcome: BatteryOptimizationRequestOutcome.failed, message: message);

  final BatteryOptimizationRequestOutcome outcome;
  final String? message;

  static BatteryOptimizationRequestResult fromMap(Object? raw) {
    if (raw is! Map) return const BatteryOptimizationRequestResult.failed();
    return BatteryOptimizationRequestResult(
      outcome: switch (raw['outcome']?.toString()) {
        'unsupported' => BatteryOptimizationRequestOutcome.unsupported,
        'alreadyAllowed' => BatteryOptimizationRequestOutcome.alreadyAllowed,
        'requested' => BatteryOptimizationRequestOutcome.requested,
        'settingsOpened' => BatteryOptimizationRequestOutcome.settingsOpened,
        _ => BatteryOptimizationRequestOutcome.failed,
      },
      message: raw['message']?.toString(),
    );
  }

  @override
  List<Object?> get props => [outcome, message];
}

class AppReleaseInfo extends Equatable {
  const AppReleaseInfo({
    required this.version,
    required this.htmlUrl,
    required this.publishedAt,
    required this.prerelease,
  });

  final SemanticVersion version;
  final String htmlUrl;
  final DateTime publishedAt;
  final bool prerelease;

  String get versionLabel => version.toString();

  @override
  List<Object?> get props => [version, htmlUrl, publishedAt, prerelease];
}
