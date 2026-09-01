// Profile list JSON in [SharedPreferences]; password and PSK in [SecretStore].
import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:tunnel_forge/features/profiles/data/profile_bridge.dart';
import 'package:tunnel_forge/features/profiles/domain/failover_group.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_transfer.dart';
import 'package:tunnel_forge/core/logging/log_entry.dart';

/// Persists sensitive strings (password, PSK) outside [SharedPreferences].
abstract class SecretStore {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> delete(String key);
}

/// Production [SecretStore] backed by [FlutterSecureStorage].
class FlutterSecureSecretStore implements SecretStore {
  FlutterSecureSecretStore([FlutterSecureStorage? storage])
    : _storage = storage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _storage;

  @override
  Future<void> delete(String key) => _storage.delete(key: key);

  @override
  Future<String?> read(String key) => _storage.read(key: key);

  @override
  Future<void> write(String key, String value) =>
      _storage.write(key: key, value: value);
}

/// In-memory [SecretStore] for tests and widget tests.
class MemorySecretStore implements SecretStore {
  final Map<String, String> _data = {};

  @override
  Future<void> delete(String key) async => _data.remove(key);

  @override
  Future<String?> read(String key) async => _data[key];

  @override
  Future<void> write(String key, String value) async => _data[key] = value;
}

/// Profiles from the host's store, application settings from [SharedPreferences].
///
/// The profiles moved to Kotlin in phase 8 — the service has to be able to read
/// one when the system starts it and no Dart code is running (SPEC В.13). What
/// is left here is the settings that belong to the application rather than to a
/// profile, and the one-time handover of the profiles this class used to own.
///
/// Tests may inject [prefsOverride] / [secretsOverride] / [backendOverride].
class ProfileStore {
  ProfileStore({
    SharedPreferences? prefsOverride,
    SecretStore? secretsOverride,
    ProfileBackend? backendOverride,
  }) : _prefsOverride = prefsOverride,
       _secrets = secretsOverride ?? FlutterSecureSecretStore(),
       _backend = backendOverride ?? MethodChannelProfileBackend();

  static const prefsKeyProfilesJson = 'vpn_profiles_json_v3';
  static const prefsKeyLastProfileId = 'vpn_last_profile_id_v1';

  /// The failover group the connect button would start, when one is chosen
  /// instead of a profile (SPEC 10.1).
  ///
  /// It lives in preferences rather than beside the last profile id in the
  /// host's store because nothing on the host needs it: a group is only ever
  /// started by this application, while the last *profile* is what the service
  /// reads when the system starts it with no Dart running (SPEC В.13).
  static const prefsKeyLastGroupId = 'vpn_last_failover_group_id_v1';
  static const prefsKeyConnectionMode = 'connection_mode_v1';
  static const prefsKeyProxyHttpPort = 'proxy_http_port_v1';
  static const prefsKeyProxySocksPort = 'proxy_socks_port_v1';
  static const prefsKeyProxyAllowLanConnections =
      'proxy_allow_lan_connections_v1';
  static const prefsKeySplitTunnelEnabled = 'split_tunnel_enabled_v1';
  static const prefsKeySplitTunnelMode = 'split_tunnel_mode_v1';
  static const prefsKeySplitTunnelInclusivePackages =
      'split_tunnel_inclusive_packages_v1';
  static const prefsKeySplitTunnelExclusivePackages =
      'split_tunnel_exclusive_packages_v1';
  static const prefsKeyConnectivityCheckUrl = 'connectivity_check_url_v1';
  static const prefsKeyConnectivityCheckTimeoutMs =
      'connectivity_check_timeout_ms_v1';
  static const prefsKeyLogDisplayLevel = 'log_display_level_v1';
  static const prefsKeyBatteryOptimizationConnectPromptShown =
      'battery_optimization_connect_prompt_shown_v1';
  static const prefsKeyUpdateCheckConsentGranted =
      'update_check_consent_granted_v1';

  final SharedPreferences? _prefsOverride;
  final SecretStore _secrets;
  final ProfileBackend _backend;

  String _passwordKey(String profileId) =>
      'tunnel_forge/profile/$profileId/password';
  String _pskKey(String profileId) => 'tunnel_forge/profile/$profileId/psk';

  Future<SharedPreferences> _prefs() async =>
      _prefsOverride ?? SharedPreferences.getInstance();

  static String newProfileId() {
    final b = Uint8List.fromList(
      List<int>.generate(16, (_) => Random.secure().nextInt(256)),
    );
    return base64UrlEncode(b).replaceAll('=', '');
  }

  /// Runs at most once per store; see [migrateLegacyProfiles].
  Future<bool>? _migration;

  /// The handover of the old Flutter-side profiles, awaited by every read.
  ///
  /// It happens here rather than at startup so that nothing can get in ahead of
  /// it and see an empty store on the first launch after the upgrade — which is
  /// what "the upgrade lost my profiles" looks like from the outside, even when
  /// the handover is a moment away.
  Future<bool> _migrated() => _migration ??= migrateLegacyProfiles();

  Future<List<Profile>> loadProfiles() async {
    await _migrated();
    return _backend.list();
  }

  Future<String?> loadLastProfileId() async {
    await _migrated();
    return _backend.lastProfileId();
  }

  Future<void> setLastProfileId(String? id) => _backend.setLastProfileId(id);

  /// Every failover group, oldest first (SPEC 10.1).
  Future<List<FailoverGroup>> loadFailoverGroups() async {
    await _migrated();
    return _backend.listGroups();
  }

  /// The group with [id], or null if there is none.
  Future<FailoverGroup?> loadFailoverGroup(String id) async {
    await _migrated();
    return _backend.loadGroup(id);
  }

  /// Stores [group]; the stored copy comes back, with its budget clamped and
  /// members that no longer exist dropped.
  Future<FailoverGroup> saveFailoverGroup(FailoverGroup group) =>
      _backend.saveGroup(group);

  Future<void> deleteFailoverGroup(String id) async {
    if (id.isEmpty) return;
    await _backend.deleteGroup(id);
    if (await loadLastGroupId() == id) await setLastGroupId(null);
  }

  Future<String?> loadLastGroupId() async {
    final p = await _prefs();
    final id = p.getString(prefsKeyLastGroupId);
    return (id == null || id.isEmpty) ? null : id;
  }

  Future<void> setLastGroupId(String? id) async {
    final p = await _prefs();
    if (id == null || id.isEmpty) {
      await p.remove(prefsKeyLastGroupId);
      return;
    }
    await p.setString(prefsKeyLastGroupId, id);
  }

  /// Wraps [payload] in a password-encrypted container (SPEC 8.1.4).
  Future<String> sealExport(String payload, String password) =>
      _backend.seal(payload, password);

  /// Unwraps a container produced by [sealExport].
  Future<String> openExport(String payload, String password) =>
      _backend.open(payload, password);

  /// Hands the profiles this class used to own to the host (SPEC 8.1.3).
  ///
  /// Runs on the first start after the upgrade; the host ignores a second call,
  /// so a profile edited after the move is never overwritten by the snapshot
  /// left in preferences. The old secrets are deleted once the host has them:
  /// a second copy of every VPN password is worse than a downgrade being
  /// inconvenient. The profile list itself stays where it is, because it holds
  /// nothing secret and is the only record of what was migrated.
  Future<bool> migrateLegacyProfiles() async {
    final legacy = await loadLegacyProfiles();
    final rows = <ProfileSecrets>[];
    for (final profile in legacy) {
      rows.add(
        ProfileSecrets(
          profile: profile,
          password: await _secrets.read(_passwordKey(profile.id)) ?? '',
          psk: await _secrets.read(_pskKey(profile.id)) ?? '',
        ),
      );
    }
    final imported = await _backend.importLegacy(rows);
    if (!imported) return false;
    final p = await _prefs();
    final last = p.getString(prefsKeyLastProfileId);
    if (last != null && last.isNotEmpty) await setLastProfileId(last);
    for (final profile in legacy) {
      await _secrets.delete(_passwordKey(profile.id));
      await _secrets.delete(_pskKey(profile.id));
    }
    return true;
  }

  /// The profiles as the build before phase 8 stored them.
  Future<List<Profile>> loadLegacyProfiles() async {
    final p = await _prefs();
    final raw = p.getString(prefsKeyProfilesJson);
    if (raw == null || raw.isEmpty) return [];
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! List) return [];
      final out = <Profile>[];
      for (final e in decoded) {
        final row = Profile.tryFromJson(e);
        if (row != null) out.add(row);
      }
      return out;
    } catch (_) {
      return [];
    }
  }

  Future<ConnectionMode> loadConnectionMode() async {
    final p = await _prefs();
    return ConnectionMode.fromJson(p.getString(prefsKeyConnectionMode));
  }

  Future<void> saveConnectionMode(ConnectionMode mode) async {
    final p = await _prefs();
    await p.setString(prefsKeyConnectionMode, mode.jsonValue);
  }

  Future<ProxySettings> loadProxySettings() async {
    final p = await _prefs();
    return ProxySettings(
      httpPort: ProxySettings.normalizePort(
        p.getInt(prefsKeyProxyHttpPort) ?? ProxySettings.defaultHttpPort,
        fallback: ProxySettings.defaultHttpPort,
      ),
      socksPort: ProxySettings.normalizePort(
        p.getInt(prefsKeyProxySocksPort) ?? ProxySettings.defaultSocksPort,
        fallback: ProxySettings.defaultSocksPort,
      ),
      allowLanConnections: p.getBool(prefsKeyProxyAllowLanConnections) ?? false,
    );
  }

  Future<void> saveProxySettings(ProxySettings settings) async {
    final p = await _prefs();
    await p.setInt(
      prefsKeyProxyHttpPort,
      ProxySettings.normalizePort(
        settings.httpPort,
        fallback: ProxySettings.defaultHttpPort,
      ),
    );
    await p.setInt(
      prefsKeyProxySocksPort,
      ProxySettings.normalizePort(
        settings.socksPort,
        fallback: ProxySettings.defaultSocksPort,
      ),
    );
    await p.setBool(
      prefsKeyProxyAllowLanConnections,
      settings.allowLanConnections,
    );
  }

  Future<SplitTunnelSettings> loadSplitTunnelSettings() async {
    final p = await _prefs();
    return SplitTunnelSettings(
      enabled: p.getBool(prefsKeySplitTunnelEnabled) ?? false,
      mode: SplitTunnelMode.fromJson(p.getString(prefsKeySplitTunnelMode)),
      inclusivePackages: SplitTunnelSettings.normalizePackages(
        p.getStringList(prefsKeySplitTunnelInclusivePackages) ?? const [],
      ),
      exclusivePackages: SplitTunnelSettings.normalizePackages(
        p.getStringList(prefsKeySplitTunnelExclusivePackages) ?? const [],
      ),
    );
  }

  Future<void> saveSplitTunnelSettings(SplitTunnelSettings settings) async {
    final p = await _prefs();
    final normalized = settings.copyWith();
    await p.setBool(prefsKeySplitTunnelEnabled, normalized.enabled);
    await p.setString(prefsKeySplitTunnelMode, normalized.mode.jsonValue);
    await p.setStringList(
      prefsKeySplitTunnelInclusivePackages,
      normalized.inclusivePackages,
    );
    await p.setStringList(
      prefsKeySplitTunnelExclusivePackages,
      normalized.exclusivePackages,
    );
  }

  Future<ConnectivityCheckSettings> loadConnectivityCheckSettings() async {
    final p = await _prefs();
    return ConnectivityCheckSettings(
      url: ConnectivityCheckSettings.normalizeUrl(
        p.getString(prefsKeyConnectivityCheckUrl) ??
            ConnectivityCheckSettings.defaultUrl,
      ),
      timeoutMs: ConnectivityCheckSettings.normalizeTimeoutMs(
        p.getInt(prefsKeyConnectivityCheckTimeoutMs) ??
            ConnectivityCheckSettings.defaultTimeoutMs,
      ),
    );
  }

  Future<void> saveConnectivityCheckSettings(
    ConnectivityCheckSettings settings,
  ) async {
    final p = await _prefs();
    await p.setString(
      prefsKeyConnectivityCheckUrl,
      ConnectivityCheckSettings.normalizeUrl(settings.url),
    );
    await p.setInt(
      prefsKeyConnectivityCheckTimeoutMs,
      ConnectivityCheckSettings.normalizeTimeoutMs(settings.timeoutMs),
    );
  }

  Future<LogDisplayLevel> loadLogDisplayLevel() async {
    final p = await _prefs();
    return LogDisplayLevel.fromStorage(p.getString(prefsKeyLogDisplayLevel));
  }

  Future<void> saveLogDisplayLevel(LogDisplayLevel level) async {
    final p = await _prefs();
    await p.setString(prefsKeyLogDisplayLevel, level.storageValue);
  }

  Future<bool> loadBatteryOptimizationConnectPromptShown() async {
    final p = await _prefs();
    return p.getBool(prefsKeyBatteryOptimizationConnectPromptShown) ?? false;
  }

  Future<void> saveBatteryOptimizationConnectPromptShown(bool shown) async {
    final p = await _prefs();
    await p.setBool(prefsKeyBatteryOptimizationConnectPromptShown, shown);
  }

  Future<bool> loadUpdateCheckConsentGranted() async {
    final p = await _prefs();
    return p.getBool(prefsKeyUpdateCheckConsentGranted) ?? false;
  }

  Future<void> saveUpdateCheckConsentGranted(bool granted) async {
    final p = await _prefs();
    await p.setBool(prefsKeyUpdateCheckConsentGranted, granted);
  }

  Future<void> upsertProfile(
    Profile profile, {
    required String password,
    required String psk,
    String proxyPassword = '',
  }) async {
    await _backend.save(
      profile,
      password: password,
      psk: psk,
      proxyPassword: proxyPassword,
    );
    await setLastProfileId(profile.id);
  }

  Future<Profile> saveImportedProfile(
    ProfileTransferEnvelope envelope, {
    bool selectAsLastProfile = true,
  }) async {
    final imported = await _backend.save(
      envelope.toProfile(newProfileId()),
      password: envelope.password,
      psk: envelope.psk,
      proxyPassword: envelope.proxyPassword,
    );
    if (selectAsLastProfile) {
      await setLastProfileId(imported.id);
    }
    return imported;
  }

  Future<void> deleteProfile(String id) async {
    if (id.isEmpty) return;
    await _backend.delete(id);
  }

  Future<
    ({Profile profile, String password, String psk, String proxyPassword})?
  >
  loadProfileWithSecrets(String id) async {
    await _migrated();
    final row = await _backend.load(id);
    if (row == null) return null;
    return (
      profile: row.profile,
      password: row.password,
      psk: row.psk,
      proxyPassword: row.proxyPassword,
    );
  }
}
