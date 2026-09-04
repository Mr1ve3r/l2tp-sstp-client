// The profile store lives in Kotlin (SPEC phase 8); this is how Dart reaches it.
import 'package:flutter/services.dart';

import 'package:tunnel_forge/features/profiles/domain/failover_group.dart';
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

  /// Failover groups (SPEC 10.1). Their members are profile ids, in order.
  static const String listGroups = 'listFailoverGroups';
  static const String loadGroup = 'loadFailoverGroup';
  static const String saveGroup = 'saveFailoverGroup';
  static const String deleteGroup = 'deleteFailoverGroup';

  static const String argGroup = 'group';

  static const String argId = 'id';
  static const String argProfile = 'profile';
  static const String argProfiles = 'profiles';
  static const String argPassword = 'password';
  static const String argPsk = 'psk';
  static const String argProxyPassword = 'proxyPassword';
  static const String argPayload = 'payload';

  /// The host could not open a container with the password it was given.
  static const String errorBadPassword = 'profile_container_password';

  /// The host refused what it was sent — an unnamed group, for instance.
  static const String errorBadArgs = 'bad_args';
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

  /// Every failover group, oldest first (SPEC 10.1).
  Future<List<FailoverGroup>> listGroups();

  /// The group with [id], or null if there is none.
  Future<FailoverGroup?> loadGroup(String id);

  /// Stores [group] and its membership, and returns it as it was stored.
  ///
  /// The store is the authority on the result: it clamps the budget, fills in
  /// the id and creation time of a new group, and drops members that no longer
  /// name a profile. The editor shows what came back rather than what it sent.
  Future<FailoverGroup> saveGroup(FailoverGroup group);

  /// Removes [group]. Its membership goes with it; the profiles stay.
  Future<void> deleteGroup(String id);
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

  @override
  Future<List<FailoverGroup>> listGroups() async {
    final raw = await _channel.invokeMethod<List<Object?>>(
      ProfileChannelContract.listGroups,
    );
    final out = <FailoverGroup>[];
    for (final entry in raw ?? const <Object?>[]) {
      final group = FailoverGroup.tryFromJson(_asMap(entry));
      if (group != null) out.add(group);
    }
    return out;
  }

  @override
  Future<FailoverGroup?> loadGroup(String id) async {
    final raw = await _channel.invokeMethod<Map<Object?, Object?>>(
      ProfileChannelContract.loadGroup,
      <String, Object?>{ProfileChannelContract.argId: id},
    );
    return FailoverGroup.tryFromJson(_asMap(raw));
  }

  @override
  Future<FailoverGroup> saveGroup(FailoverGroup group) async {
    final payload = group.toJson();
    // A group that has never been stored has no creation time to send. Leaving
    // the key out lets the host stamp one; sending the zero would date every
    // new group to 1970 and scramble the order the list is shown in.
    if (group.createdAt <= 0) payload.remove('createdAt');
    final raw = await _channel.invokeMethod<Map<Object?, Object?>>(
      ProfileChannelContract.saveGroup,
      <String, Object?>{ProfileChannelContract.argGroup: payload},
    );
    return FailoverGroup.tryFromJson(_asMap(raw)) ?? group;
  }

  @override
  Future<void> deleteGroup(String id) async {
    await _channel.invokeMethod<Object?>(
      ProfileChannelContract.deleteGroup,
      <String, Object?>{ProfileChannelContract.argId: id},
    );
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
  final Map<String, FailoverGroup> _groups = <String, FailoverGroup>{};
  String? _lastProfileId;
  bool _legacyImportDone = false;
  int _nextGroupId = 1;

  @override
  Future<void> delete(String id) async {
    _rows.remove(id);
    if (_lastProfileId == id) _lastProfileId = null;
    // The host's membership rows cascade off a deleted profile; without this
    // the fake would keep offering a member the real store has already
    // dropped, and a test would pass on behaviour production does not have.
    for (final groupId in _groups.keys.toList()) {
      final group = _groups[groupId]!;
      if (!group.memberIds.contains(id)) continue;
      _groups[groupId] = group.copyWith(
        memberIds: group.memberIds.where((member) => member != id).toList(),
      );
    }
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

  @override
  Future<List<FailoverGroup>> listGroups() async =>
      _groups.values.toList(growable: false);

  @override
  Future<FailoverGroup?> loadGroup(String id) async => _groups[id];

  @override
  Future<FailoverGroup> saveGroup(FailoverGroup group) async {
    final name = group.name.trim();
    if (name.isEmpty) {
      throw PlatformException(
        code: ProfileChannelContract.errorBadArgs,
        message: 'A group needs a name',
      );
    }
    final id = group.id.isEmpty ? _newGroupId() : group.id;
    final members = <String>[];
    for (final memberId in group.memberIds) {
      if (_rows.containsKey(memberId) && !members.contains(memberId)) {
        members.add(memberId);
      }
    }
    final stored = group.copyWith(
      id: id,
      name: name,
      connectTimeoutSec: FailoverGroup.normalizeTimeout(
        group.connectTimeoutSec,
      ),
      createdAt: group.createdAt > 0
          ? group.createdAt
          : (_groups[id]?.createdAt ?? DateTime.now().millisecondsSinceEpoch),
      memberIds: members,
    );
    _groups[id] = stored;
    return stored;
  }

  @override
  Future<void> deleteGroup(String id) async => _groups.remove(id);

  /// Unique within one fake store, which is all a test needs; the host's ids
  /// come from the same generator the profiles use.
  String _newGroupId() => 'group-${_nextGroupId++}';
}
