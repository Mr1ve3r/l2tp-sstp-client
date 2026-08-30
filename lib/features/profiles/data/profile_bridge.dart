// The profile store lives in Kotlin (SPEC phase 8); this is how Dart reaches it.
import 'package:flutter/services.dart';

import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';

/// Method channel contract for the profile store. Mirrors `ProfileContract.kt`.
class ProfileChannelContract {
  const ProfileChannelContract._();

  static const String channelName =
      'io.github.evokelektrique.tunnelforge/profiles';

  static const String listProfiles = 'listProfiles';
  static const String loadProfile = 'loadProfile';
  static const String saveProfile = 'saveProfile';
  static const String deleteProfile = 'deleteProfile';
  static const String lastProfileId = 'lastProfileId';
  static const String setLastProfileId = 'setLastProfileId';
  static const String importLegacyProfiles = 'importLegacyProfiles';
  static const String sealExport = 'sealExport';
  static const String openExport = 'openExport';

  static const String argId = 'id';
  static const String argProfile = 'profile';
  static const String argProfiles = 'profiles';
  static const String argPassword = 'password';
  static const String argPsk = 'psk';
  static const String argProxyPassword = 'proxyPassword';
  static const String argPayload = 'payload';

  /// The host could not open a container with the password it was given.
  static const String errorBadPassword = 'profile_container_password';
}

/// A profile with the secrets the host keeps out of the profile itself.
class ProfileSecrets {
  const ProfileSecrets({
    required this.profile,
    required this.password,
    required this.psk,
    this.proxyPassword = '',
  });

  final Profile profile;
  final String password;
  final String psk;
  final String proxyPassword;
}

/// The profile store, wherever it happens to live.
///
/// Two implementations: the host's, and an in-memory one for tests. The store
/// is not something Dart can substitute for at runtime — there is exactly one,
/// and it is the host's — but a widget test has no host to talk to.
abstract class ProfileBackend {
  Future<List<Profile>> list();
  Future<ProfileSecrets?> load(String id);
  Future<Profile> save(
    Profile profile, {
    required String password,
    required String psk,
    String proxyPassword = '',
  });
  Future<void> delete(String id);
  Future<String?> lastProfileId();
  Future<void> setLastProfileId(String? id);

  /// Hands the profiles Flutter used to own to the host. Runs once (SPEC 8.1.3).
  Future<bool> importLegacy(List<ProfileSecrets> rows);

  /// Wraps [payload] in a password-encrypted container (SPEC 8.1.4).
  Future<String> seal(String payload, String password);

  /// Unwraps a container produced by [seal].
  Future<String> open(String payload, String password);
}

/// [ProfileBackend] over the method channel to the host.
class MethodChannelProfileBackend implements ProfileBackend {
  MethodChannelProfileBackend([MethodChannel? channel])
    : _channel =
          channel ?? const MethodChannel(ProfileChannelContract.channelName);

  final MethodChannel _channel;

  @override
  Future<List<Profile>> list() async {
    final raw = await _channel.invokeMethod<List<Object?>>(
      ProfileChannelContract.listProfiles,
    );
    final out = <Profile>[];
    for (final entry in raw ?? const <Object?>[]) {
      final profile = Profile.tryFromJson(_asMap(entry));
      if (profile != null) out.add(profile);
    }
    return out;
  }

  @override
  Future<ProfileSecrets?> load(String id) async {
    final raw = await _channel.invokeMethod<Map<Object?, Object?>>(
      ProfileChannelContract.loadProfile,
      <String, Object?>{ProfileChannelContract.argId: id},
    );
    if (raw == null) return null;
    final profile = Profile.tryFromJson(
      _asMap(raw[ProfileChannelContract.argProfile]),
    );
    if (profile == null) return null;
    return ProfileSecrets(
      profile: profile,
      password: raw[ProfileChannelContract.argPassword] as String? ?? '',
      psk: raw[ProfileChannelContract.argPsk] as String? ?? '',
      proxyPassword:
          raw[ProfileChannelContract.argProxyPassword] as String? ?? '',
    );
  }

  @override
  Future<Profile> save(
    Profile profile, {
    required String password,
    required String psk,
    String proxyPassword = '',
  }) async {
    final raw = await _channel.invokeMethod<Map<Object?, Object?>>(
      ProfileChannelContract.saveProfile,
      <String, Object?>{
        ProfileChannelContract.argProfile: profile.toJson(),
        ProfileChannelContract.argPassword: password,
        ProfileChannelContract.argPsk: psk,
        ProfileChannelContract.argProxyPassword: proxyPassword,
      },
    );
    return Profile.tryFromJson(_asMap(raw)) ?? profile;
  }

  @override
  Future<void> delete(String id) async {
    await _channel.invokeMethod<Object?>(
      ProfileChannelContract.deleteProfile,
      <String, Object?>{ProfileChannelContract.argId: id},
    );
  }

  @override
  Future<String?> lastProfileId() =>
      _channel.invokeMethod<String>(ProfileChannelContract.lastProfileId);

  @override
  Future<void> setLastProfileId(String? id) async {
    await _channel.invokeMethod<Object?>(
      ProfileChannelContract.setLastProfileId,
      <String, Object?>{ProfileChannelContract.argId: id},
    );
  }

  @override
  Future<bool> importLegacy(List<ProfileSecrets> rows) async {
    final payload = rows
        .map(
          (row) => <String, Object?>{
            ProfileChannelContract.argProfile: row.profile.toJson(),
            ProfileChannelContract.argPassword: row.password,
            ProfileChannelContract.argPsk: row.psk,
          },
        )
        .toList();
    final done = await _channel.invokeMethod<bool>(
      ProfileChannelContract.importLegacyProfiles,
      <String, Object?>{ProfileChannelContract.argProfiles: payload},
    );
    return done ?? false;
  }

  @override
  Future<String> seal(String payload, String password) async {
    final sealed = await _channel.invokeMethod<String>(
      ProfileChannelContract.sealExport,
      <String, Object?>{
        ProfileChannelContract.argPayload: payload,
        ProfileChannelContract.argPassword: password,
      },
    );
    return sealed ?? '';
  }

  @override
  Future<String> open(String payload, String password) async {
    final opened = await _channel.invokeMethod<String>(
      ProfileChannelContract.openExport,
      <String, Object?>{
        ProfileChannelContract.argPayload: payload,
        ProfileChannelContract.argPassword: password,
      },
    );
    return opened ?? '';
  }

  /// A method channel hands back `Map<Object?, Object?>`; JSON wants strings.
  static Map<String, Object?>? _asMap(Object? raw) {
    if (raw is! Map) return null;
    return raw.map((key, value) => MapEntry(key.toString(), value));
  }
}

/// [ProfileBackend] holding everything in memory, for tests.
class MemoryProfileBackend implements ProfileBackend {
  final Map<String, ProfileSecrets> _rows = <String, ProfileSecrets>{};
  String? _lastProfileId;
  bool _legacyImportDone = false;

  @override
  Future<void> delete(String id) async {
    _rows.remove(id);
    if (_lastProfileId == id) _lastProfileId = null;
  }

  @override
  Future<String?> lastProfileId() async => _lastProfileId;

  @override
  Future<List<Profile>> list() async =>
      _rows.values.map((row) => row.profile).toList();

  @override
  Future<ProfileSecrets?> load(String id) async => _rows[id];

  @override
  Future<Profile> save(
    Profile profile, {
    required String password,
    required String psk,
    String proxyPassword = '',
  }) async {
    _rows[profile.id] = ProfileSecrets(
      profile: profile,
      password: password,
      psk: psk,
      proxyPassword: proxyPassword,
    );
    return profile;
  }

  @override
  Future<void> setLastProfileId(String? id) async =>
      _lastProfileId = (id == null || id.isEmpty) ? null : id;

  @override
  Future<bool> importLegacy(List<ProfileSecrets> rows) async {
    if (_legacyImportDone) return false;
    for (final row in rows) {
      _rows[row.profile.id] = row;
    }
    _legacyImportDone = true;
    return true;
  }

  @override
  Future<String> seal(String payload, String password) async =>
      'sealed:$password:$payload';

  @override
  Future<String> open(String payload, String password) async {
    final prefix = 'sealed:$password:';
    if (!payload.startsWith(prefix)) {
      throw PlatformException(
        code: ProfileChannelContract.errorBadPassword,
        message: 'The container could not be opened with that password',
      );
    }
    return payload.substring(prefix.length);
  }
}
