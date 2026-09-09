import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:share_plus/share_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:tunnel_forge/core/platform/app_info_bridge.dart';
import 'package:tunnel_forge/core/network/connectivity_checker.dart';
import 'package:tunnel_forge/features/profiles/domain/failover_group.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/data/profile_store.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_bundle.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_transfer.dart';
import 'package:tunnel_forge/features/profiles/data/profile_transfer_bridge.dart';
import 'package:tunnel_forge/core/logging/log_entry.dart';
import 'package:tunnel_forge/features/trust/domain/trust_models.dart';
import 'package:tunnel_forge/features/trust/domain/trust_repository.dart';
import 'package:tunnel_forge/features/tunnel/data/vpn_client.dart';
import 'package:tunnel_forge/features/tunnel/domain/tunnel_runtime_state.dart';
import '../domain/home_models.dart';
import '../domain/home_repositories.dart';

class ProfilesRepositoryImpl implements ProfilesRepository {
  ProfilesRepositoryImpl(this._profileStore, [this._certificates]);

  final ProfileStore _profileStore;

  /// The certificate store, so an SSTP profile can travel with the
  /// certificates it trusts (SPEC 8.1.4). Absent in tests that never
  /// touch one.
  final CertificatesRepository? _certificates;

  @override
  Future<void> copyProfileShareLink(String id) async {
    final envelope = await _envelopeFor(id);
    await Clipboard.setData(ClipboardData(text: envelope.toTfUri()));
  }

  @override
  Future<void> deleteProfile(String id) => _profileStore.deleteProfile(id);

  @override
  Future<List<FailoverGroup>> loadFailoverGroups() =>
      _profileStore.loadFailoverGroups();

  @override
  Future<FailoverGroup> saveFailoverGroup(FailoverGroup group) =>
      _profileStore.saveFailoverGroup(group);

  @override
  Future<void> deleteFailoverGroup(String id) =>
      _profileStore.deleteFailoverGroup(id);

  @override
  Future<String?> loadLastGroupId() => _profileStore.loadLastGroupId();

  @override
  Future<void> setLastGroupId(String? id) => _profileStore.setLastGroupId(id);

  @override
  Future<void> exportProfileFile(
    String id, {
    String? password,
    bool includeConnectivityCheck = true,
  }) async {
    final envelope = await _envelopeFor(id);
    // Without a password the file carries no secret at all. With one it
    // carries them inside the container and nowhere else (SPEC 8.1.4).
    final text = password == null || password.isEmpty
        ? envelope.toFileJson(
            includeConnectivityCheck: includeConnectivityCheck,
          )
        : await _profileStore.sealExport(
            envelope.toFileJson(
              secrets: TransferSecrets.all,
              includeConnectivityCheck: includeConnectivityCheck,
            ),
            password,
          );
    final bytes = Uint8List.fromList(utf8.encode(text));
    final fileName = ProfileTransferEnvelope.exportFileNameFor(
      envelope.profile,
    );
    await SharePlus.instance.share(
      ShareParams(
        files: [
          XFile.fromData(bytes, mimeType: ProfileTransferEnvelope.mimeType),
        ],
        fileNameOverrides: [fileName],
        title: 'Export TunnelForge profile',
      ),
    );
  }

  @override
  Future<void> exportProfileBundle({
    required List<String> profileIds,
    required String bundleName,
    required String password,
    List<String> groupIds = const <String>[],
    bool includeConnectivityCheck = true,
    TransferSecrets secrets = TransferSecrets.shared,
  }) async {
    if (profileIds.isEmpty) {
      throw const ProfileRepositoryException(
        'Choose at least one profile to put in the set.',
      );
    }
    if (password.isEmpty) {
      throw const ProfileRepositoryException(
        'A set needs a password: it carries the pre-shared key.',
      );
    }
    final entries = <ProfileTransferEnvelope>[];
    for (final id in profileIds) {
      entries.add(await _envelopeFor(id));
    }
    final bundle = ProfileBundle(
      name: bundleName.trim(),
      entries: entries,
      createdAt: DateTime.now().toUtc(),
      groups: await _bundledGroups(groupIds, profileIds),
    );
    final sealed = await _profileStore.sealExport(
      bundle.toFileJson(
        secrets: secrets,
        includeConnectivityCheck: includeConnectivityCheck,
      ),
      password,
    );
    await SharePlus.instance.share(
      ShareParams(
        files: [
          XFile.fromData(
            Uint8List.fromList(utf8.encode(sealed)),
            mimeType: ProfileTransferEnvelope.mimeType,
          ),
        ],
        fileNameOverrides: [ProfileBundle.exportFileNameFor(bundle.name)],
        title: 'Export TunnelForge profiles',
      ),
    );
  }

  /// The groups in [groupIds] rewritten to name their members by position in
  /// [profileIds].
  ///
  /// A group with a member outside the set is dropped. The export sheet only
  /// offers whole groups, so this is the case where the selection changed
  /// after a group was ticked rather than something the user asked for.
  Future<List<BundledFailoverGroup>> _bundledGroups(
    List<String> groupIds,
    List<String> profileIds,
  ) async {
    if (groupIds.isEmpty) return const <BundledFailoverGroup>[];
    final positionOf = <String, int>{
      for (var i = 0; i < profileIds.length; i++) profileIds[i]: i,
    };
    final wanted = groupIds.toSet();
    final bundled = <BundledFailoverGroup>[];
    for (final group in await _profileStore.loadFailoverGroups()) {
      if (!wanted.contains(group.id) || group.isEmpty) continue;
      final indexes = <int>[];
      var complete = true;
      for (final memberId in group.memberIds) {
        final position = positionOf[memberId];
        if (position == null) {
          complete = false;
          break;
        }
        indexes.add(position);
      }
      if (!complete || indexes.isEmpty) continue;
      bundled.add(
        BundledFailoverGroup(
          name: group.displayName,
          connectTimeoutSec: group.connectTimeoutSec,
          memberIndexes: indexes,
        ),
      );
    }
    return bundled;
  }

  @override
  Future<ProfileTransferDocument> openSealedTransfer(
    String payload,
    String password,
  ) async {
    return ProfileTransferDocument.parse(
      await _profileStore.openExport(payload, password),
    );
  }

  @override
  Future<Set<String>> loadProfilesAwaitingCredentials() =>
      _profileStore.loadProfilesAwaitingCredentials();

  @override
  Future<void> clearProfileAwaitingCredentials(String id) =>
      _profileStore.clearProfileAwaitingCredentials(id);

  @override
  Future<BundleImportResult> importProfileBundle({
    required ProfileBundle bundle,
    required List<BundleImportChoice> choices,
    List<int>? groupIndexes,
  }) async {
    var added = 0;
    var replaced = 0;
    var skipped = 0;
    String? firstImportedId;
    final awaitingCredentials = <String>[];
    // Which profile each entry turned into, so the set's groups can be
    // rebuilt out of the ids this device gave them.
    final landedIds = <int, String>{};
    for (final choice in choices) {
      if (choice.entryIndex < 0 || choice.entryIndex >= bundle.entries.length) {
        continue;
      }
      final entry = bundle.entries[choice.entryIndex];
      switch (choice.action) {
        case BundleImportAction.skip:
          skipped++;
        case BundleImportAction.add:
          final stored = await saveImportedProfile(
            entry,
            selectAsLastProfile: false,
          );
          added++;
          landedIds[choice.entryIndex] = stored.id;
          firstImportedId ??= stored.id;
          if (stored.needsCredentials) awaitingCredentials.add(stored.id);
        case BundleImportAction.replace:
          final targetId = choice.targetProfileId;
          if (targetId == null) {
            final stored = await saveImportedProfile(
              entry,
              selectAsLastProfile: false,
            );
            added++;
            landedIds[choice.entryIndex] = stored.id;
            firstImportedId ??= stored.id;
            if (stored.needsCredentials) awaitingCredentials.add(stored.id);
            continue;
          }
          final outcome = await _replaceProfile(targetId, entry);
          // The target can have been deleted between the sheet opening and
          // this running, in which case nothing was replaced and the summary
          // must not claim otherwise.
          if (outcome.replaced) {
            replaced++;
          } else {
            added++;
          }
          landedIds[choice.entryIndex] = outcome.profile.id;
          firstImportedId ??= outcome.profile.id;
          if (outcome.profile.needsCredentials) {
            awaitingCredentials.add(outcome.profile.id);
          }
      }
    }
    // Marked from what actually landed rather than from what the file said:
    // a replace keeps the login the recipient had already typed in, and that
    // profile is not waiting for anything.
    await _profileStore.markProfilesAwaitingCredentials(awaitingCredentials);
    final groups = await _importGroups(bundle, landedIds, groupIndexes);
    return BundleImportResult(
      added: added,
      replaced: replaced,
      skipped: skipped,
      groupsAdded: groups.added,
      groupsUpdated: groups.updated,
      firstImportedId: firstImportedId,
    );
  }

  /// Stores the set's failover groups against the profiles that landed.
  ///
  /// A member the recipient chose to skip is left out rather than made to
  /// block its group: a group of the three sites they took is worth having,
  /// and an id for a profile that was never stored could not be saved anyway.
  ///
  /// A group whose name and membership are already here is rewritten instead
  /// of duplicated. The same set is handed out again whenever the organisation
  /// changes something, and the third handout should not leave three groups
  /// called the same thing.
  Future<({int added, int updated})> _importGroups(
    ProfileBundle bundle,
    Map<int, String> landedIds,
    List<int>? groupIndexes,
  ) async {
    if (bundle.groups.isEmpty) return (added: 0, updated: 0);
    final existing = await _profileStore.loadFailoverGroups();
    final taken = existing.map((group) => group.displayName).toSet();
    var added = 0;
    var updated = 0;
    for (var index = 0; index < bundle.groups.length; index++) {
      if (groupIndexes != null && !groupIndexes.contains(index)) continue;
      final bundled = bundle.groups[index];
      final memberIds = <String>[];
      for (final index in bundled.memberIndexes) {
        final id = landedIds[index];
        if (id != null) memberIds.add(id);
      }
      if (memberIds.isEmpty) continue;
      final match = existing
          .where(
            (group) =>
                group.displayName.toLowerCase() == bundled.name.toLowerCase() &&
                _sameMembers(group.memberIds, memberIds),
          )
          .firstOrNull;
      if (match != null) {
        await _profileStore.saveFailoverGroup(
          match.copyWith(connectTimeoutSec: bundled.connectTimeoutSec),
        );
        updated++;
        continue;
      }
      final name = _unusedGroupName(bundled.name, taken);
      taken.add(name);
      // An empty id is how `ProfileChannel.readGroup` is already told to mint
      // one and stamp the creation time.
      await _profileStore.saveFailoverGroup(
        bundled
            .toFailoverGroup(id: '', memberIds: memberIds)
            .copyWith(name: name),
      );
      added++;
    }
    return (added: added, updated: updated);
  }

  /// Whether two membership lists name the same profiles in the same order.
  static bool _sameMembers(List<String> a, List<String> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }

  /// [name], or [name] with a counter, so two different groups do not end up
  /// sharing one label in the picker.
  static String _unusedGroupName(String name, Set<String> taken) {
    if (!taken.any((used) => used.toLowerCase() == name.toLowerCase())) {
      return name;
    }
    for (var suffix = 2; ; suffix++) {
      final candidate = '$name ($suffix)';
      if (!taken.any((used) => used.toLowerCase() == candidate.toLowerCase())) {
        return candidate;
      }
    }
  }

  /// Overwrites [targetId] with [entry], keeping what the set left out.
  ///
  /// The login and password are the recipient's own, typed in after the last
  /// handout; a new handout carries neither, so taking the incoming empty
  /// fields at face value would sign them out every time the organisation
  /// changed an MTU.
  Future<({Profile profile, bool replaced})> _replaceProfile(
    String targetId,
    ProfileTransferEnvelope entry,
  ) async {
    final existing = await _profileStore.loadProfileWithSecrets(targetId);
    if (existing == null) {
      return (
        profile: await saveImportedProfile(entry, selectAsLastProfile: false),
        replaced: false,
      );
    }
    final incoming = entry.toProfile(targetId);
    final merged = incoming.copyWith(
      user: entry.profile.user.trim().isEmpty
          ? existing.profile.user
          : entry.profile.user,
      proxyUsername: entry.profile.proxyUsername.trim().isEmpty
          ? existing.profile.proxyUsername
          : entry.profile.proxyUsername,
      trustedCertificateIds: await _storeCertificatesOf(entry),
    );
    await _profileStore.upsertProfile(
      merged,
      password: entry.password.isEmpty ? existing.password : entry.password,
      psk: entry.psk.isEmpty ? existing.psk : entry.psk,
      proxyPassword: entry.proxyPassword.isEmpty
          ? existing.proxyPassword
          : entry.proxyPassword,
    );
    return (profile: merged, replaced: true);
  }

  /// Imports the certificates [entry] brought and returns their ids here.
  ///
  /// The ids in the file are the sending device's fingerprints; the store
  /// recomputes them, which is what makes a certificate six profiles share one
  /// entry rather than six.
  Future<List<String>> _storeCertificatesOf(
    ProfileTransferEnvelope entry,
  ) async {
    final store = _certificates;
    if (entry.certificates.isEmpty || store == null) return const <String>[];
    final stored = await store.import(
      entry.certificates
          .map(
            (certificate) => CertificateImportRequest(
              pem: certificate.pem,
              alias: certificate.alias,
            ),
          )
          .toList(),
    );
    return stored.map((e) => e.fields.id).toList(growable: false);
  }

  /// The profile [id] with its secrets and the certificates it selects.
  Future<ProfileTransferEnvelope> _envelopeFor(String id) async {
    final row = await _profileStore.loadProfileWithSecrets(id);
    if (row == null) {
      throw const ProfileRepositoryException('This profile no longer exists.');
    }
    final certificates = <TransferredCertificate>[];
    final store = _certificates;
    if (store != null && row.profile.trustedCertificateIds.isNotEmpty) {
      final stored = await store.list();
      for (final certificateId in row.profile.trustedCertificateIds) {
        final pem = await store.exportPem(certificateId);
        if (pem == null || pem.isEmpty) continue;
        certificates.add(
          TransferredCertificate(
            id: certificateId,
            alias:
                stored
                    .where((entry) => entry.fields.id == certificateId)
                    .map((entry) => entry.alias)
                    .firstOrNull ??
                '',
            pem: pem,
          ),
        );
      }
    }
    return ProfileTransferEnvelope.fromProfile(
      profile: row.profile,
      password: row.password,
      psk: row.psk,
      proxyPassword: row.proxyPassword,
      certificates: certificates,
    );
  }

  @override
  Future<String?> loadLastProfileId() => _profileStore.loadLastProfileId();

  @override
  Future<List<Profile>> loadProfiles() => _profileStore.loadProfiles();

  @override
  Future<ProfileSecretRow?> loadProfileWithSecrets(String id) async {
    final row = await _profileStore.loadProfileWithSecrets(id);
    if (row == null) return null;
    return ProfileSecretRow(
      profile: row.profile,
      password: row.password,
      psk: row.psk,
      proxyPassword: row.proxyPassword,
    );
  }

  @override
  Future<TrustOptions> loadTrustOptions() async {
    final store = _certificates;
    if (store == null) return const TrustOptions();
    return TrustOptions(
      certificates: await store.list(),
      policies: await store.policies(),
    );
  }

  @override
  String newProfileId() => ProfileStore.newProfileId();

  @override
  Future<Profile> saveImportedProfile(
    ProfileTransferEnvelope envelope, {
    bool selectAsLastProfile = true,
  }) async {
    final imported = await _profileStore.saveImportedProfile(
      envelope,
      selectAsLastProfile: selectAsLastProfile,
    );
    // The certificates come in under the fingerprints this device computes,
    // which is what makes an already-known certificate one entry rather than
    // two. Until they are stored, the ids in the file mean nothing here.
    final store = _certificates;
    if (envelope.certificates.isEmpty || store == null) return imported;
    final stored = await store.import(
      envelope.certificates
          .map(
            (certificate) => CertificateImportRequest(
              pem: certificate.pem,
              alias: certificate.alias,
            ),
          )
          .toList(),
    );
    if (stored.isEmpty) return imported;
    final withCertificates = imported.copyWith(
      trustedCertificateIds: stored
          .map((entry) => entry.fields.id)
          .toList(growable: false),
    );
    await _profileStore.upsertProfile(
      withCertificates,
      password: envelope.password,
      psk: envelope.psk,
      proxyPassword: envelope.proxyPassword,
    );
    return withCertificates;
  }

  @override
  Future<void> setLastProfileId(String? id) =>
      _profileStore.setLastProfileId(id);

  @override
  Future<void> upsertProfile(
    Profile profile, {
    required String password,
    required String psk,
    String proxyPassword = '',
  }) {
    return _profileStore.upsertProfile(
      profile,
      password: password,
      psk: psk,
      proxyPassword: proxyPassword,
    );
  }
}

class SettingsRepositoryImpl implements SettingsRepository {
  SettingsRepositoryImpl(this._profileStore);

  final ProfileStore _profileStore;

  @override
  Future<ConnectionMode> loadConnectionMode() {
    return _profileStore.loadConnectionMode();
  }

  @override
  Future<ConnectivityCheckSettings> loadConnectivityCheckSettings() {
    return _profileStore.loadConnectivityCheckSettings();
  }

  @override
  Future<SystemSurfaceSettings> loadSystemSurfaceSettings() {
    return _profileStore.loadSystemSurfaceSettings();
  }

  @override
  Future<SystemSurfaceSettings> saveSystemSurfaceSettings(
    SystemSurfaceSettings settings,
  ) {
    return _profileStore.saveSystemSurfaceSettings(settings);
  }

  @override
  Future<LogDisplayLevel> loadLogDisplayLevel() {
    return _profileStore.loadLogDisplayLevel();
  }

  @override
  Future<bool> loadBatteryOptimizationConnectPromptShown() {
    return _profileStore.loadBatteryOptimizationConnectPromptShown();
  }

  @override
  Future<bool> loadUpdateCheckConsentGranted() {
    return _profileStore.loadUpdateCheckConsentGranted();
  }

  @override
  Future<ProxySettings> loadProxySettings() {
    return _profileStore.loadProxySettings();
  }

  @override
  Future<SplitTunnelSettings> loadSplitTunnelSettings() {
    return _profileStore.loadSplitTunnelSettings();
  }

  @override
  Future<void> saveConnectionMode(ConnectionMode mode) {
    return _profileStore.saveConnectionMode(mode);
  }

  @override
  Future<void> saveConnectivityCheckSettings(
    ConnectivityCheckSettings settings,
  ) {
    return _profileStore.saveConnectivityCheckSettings(settings);
  }

  @override
  Future<void> saveLogDisplayLevel(LogDisplayLevel level) {
    return _profileStore.saveLogDisplayLevel(level);
  }

  @override
  Future<void> saveBatteryOptimizationConnectPromptShown(bool shown) {
    return _profileStore.saveBatteryOptimizationConnectPromptShown(shown);
  }

  @override
  Future<void> saveUpdateCheckConsentGranted(bool granted) {
    return _profileStore.saveUpdateCheckConsentGranted(granted);
  }

  @override
  Future<void> saveProxySettings(ProxySettings settings) {
    return _profileStore.saveProxySettings(settings);
  }

  @override
  Future<void> saveSplitTunnelSettings(SplitTunnelSettings settings) {
    return _profileStore.saveSplitTunnelSettings(settings);
  }
}

class AppVersionRepositoryImpl implements AppVersionRepository {
  AppVersionRepositoryImpl({
    Future<PackageInfo> Function()? packageInfoLoader,
    Future<({String? versionName, String? buildNumber})> Function()?
    nativeVersionLoader,
  }) : _packageInfoLoader = packageInfoLoader ?? PackageInfo.fromPlatform,
       _nativeVersionLoader =
           nativeVersionLoader ?? AppInfoBridge().loadInstalledVersion;

  final Future<PackageInfo> Function() _packageInfoLoader;
  final Future<({String? versionName, String? buildNumber})> Function()
  _nativeVersionLoader;

  @override
  Future<AppVersionInfo> loadInstalledVersion() async {
    Object? packageInfoError;
    try {
      final info = await _packageInfoLoader();
      final displayVersion = _displayVersion(
        versionName: info.version,
        buildNumber: info.buildNumber,
      );
      if (displayVersion != null) {
        return AppVersionInfo(
          displayVersion: displayVersion,
          semanticVersion: SemanticVersion.tryParse(displayVersion),
        );
      }
      packageInfoError = const FormatException(
        'package_info_plus returned an empty version.',
      );
    } catch (error) {
      packageInfoError = error;
    }

    try {
      final native = await _nativeVersionLoader();
      final displayVersion = _displayVersion(
        versionName: native.versionName,
        buildNumber: native.buildNumber,
      );
      if (displayVersion != null) {
        return AppVersionInfo(
          displayVersion: displayVersion,
          semanticVersion: SemanticVersion.tryParse(displayVersion),
        );
      }
      return AppVersionInfo(
        semanticVersion: null,
        errorReason:
            'Installed version unavailable. Native version lookup returned empty values.',
      );
    } catch (nativeError) {
      return AppVersionInfo(
        semanticVersion: null,
        errorReason: _versionErrorReason(packageInfoError, nativeError),
      );
    }
  }

  static String? _displayVersion({
    required String? versionName,
    required String? buildNumber,
  }) {
    final version = versionName?.trim() ?? '';
    final build = buildNumber?.trim() ?? '';
    return switch ((version.isEmpty, build.isEmpty)) {
      (false, false) => '$version+$build',
      (false, true) => version,
      (true, false) => build,
      _ => null,
    };
  }

  static String _versionErrorReason(
    Object? packageInfoError,
    Object nativeError,
  ) {
    final packageInfoMessage = packageInfoError == null
        ? 'package_info_plus reason unavailable'
        : packageInfoError.toString();
    return 'Installed version unavailable. package_info_plus failed: $packageInfoMessage. Native fallback failed: ${nativeError.toString()}.';
  }
}

class AppUpdateRepositoryImpl implements AppUpdateRepository {
  AppUpdateRepositoryImpl({
    Future<String> Function(Uri uri)? fetcher,
    // This fork's releases, not upstream's. An APK from upstream is signed
    // with a different key and cannot install over this one, so offering it as
    // an update produces a failure the user cannot act on.
    this.owner = 'Mr1ve3r',
    this.repo = 'l2tp-sstp-client',
  }) : _fetcher = fetcher ?? _fetchJson;

  final Future<String> Function(Uri uri) _fetcher;
  final String owner;
  final String repo;

  @override
  Future<AppReleaseInfo> fetchLatestRelease() async {
    final uri = Uri.https('api.github.com', '/repos/$owner/$repo/releases');
    final body = await _fetcher(uri);
    final Object decoded;
    try {
      decoded = jsonDecode(body);
    } on FormatException catch (error) {
      throw AppUpdateException(
        kind: AppUpdateErrorKind.response,
        userMessage: 'GitHub Releases returned malformed data.',
        details: error.message,
      );
    }
    if (decoded is! List) {
      throw const AppUpdateException(
        kind: AppUpdateErrorKind.response,
        userMessage: 'GitHub Releases returned malformed data.',
        details: 'Expected a GitHub releases list.',
      );
    }

    final releases =
        decoded
            .whereType<Map>()
            .map((entry) => _parseRelease(Map<String, dynamic>.from(entry)))
            .whereType<AppReleaseInfo>()
            .toList()
          ..sort(
            (left, right) => right.publishedAt.compareTo(left.publishedAt),
          );

    if (releases.isEmpty) {
      throw const AppUpdateException(
        kind: AppUpdateErrorKind.response,
        userMessage: 'GitHub Releases returned no usable releases.',
        details: 'No valid published releases were found.',
      );
    }

    return releases.first;
  }

  AppReleaseInfo? _parseRelease(Map<String, dynamic> json) {
    if (json['draft'] == true) return null;

    final publishedAtValue = json['published_at'] as String?;
    final htmlUrl = json['html_url'] as String?;
    final tagName = json['tag_name'] as String?;
    if (publishedAtValue == null || htmlUrl == null || tagName == null) {
      return null;
    }

    final publishedAt = DateTime.tryParse(publishedAtValue);
    final version = SemanticVersion.tryParse(tagName);
    if (publishedAt == null || version == null) return null;

    return AppReleaseInfo(
      version: version,
      htmlUrl: htmlUrl,
      publishedAt: publishedAt,
      prerelease: json['prerelease'] == true,
    );
  }

  static Future<String> _fetchJson(Uri uri) async {
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 5);
    try {
      final request = await client.getUrl(uri);
      request.headers.set(HttpHeaders.acceptHeader, 'application/json');
      request.headers.set(HttpHeaders.userAgentHeader, 'TunnelForgeApp/1.0');
      request.headers.set('X-GitHub-Api-Version', '2022-11-28');
      final response = await request.close();
      final body = await response.transform(utf8.decoder).join();
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw AppUpdateException(
          kind: AppUpdateErrorKind.http,
          userMessage: 'GitHub Releases returned HTTP ${response.statusCode}.',
          statusCode: response.statusCode,
          details: body.isEmpty ? null : body,
        );
      }
      return body;
    } on TimeoutException {
      throw const AppUpdateException(
        kind: AppUpdateErrorKind.timeout,
        userMessage: 'GitHub Releases request timed out.',
      );
    } on HandshakeException catch (error) {
      throw AppUpdateException(
        kind: AppUpdateErrorKind.tls,
        userMessage: 'Secure connection to GitHub Releases failed.',
        details: error.message,
      );
    } on SocketException catch (error) {
      throw AppUpdateException(
        kind: AppUpdateErrorKind.network,
        userMessage: 'Network error while contacting GitHub Releases.',
        details: error.message,
      );
    } on AppUpdateException {
      rethrow;
    } on FormatException catch (error) {
      throw AppUpdateException(
        kind: AppUpdateErrorKind.response,
        userMessage: 'GitHub Releases returned malformed data.',
        details: error.message,
      );
    } catch (error) {
      throw AppUpdateException(
        kind: AppUpdateErrorKind.unknown,
        userMessage: 'Unexpected error while checking GitHub Releases.',
        details: error.toString(),
      );
    } finally {
      client.close(force: true);
    }
  }
}

class TunnelRepositoryImpl implements TunnelRepository {
  TunnelRepositoryImpl({VpnClient? client}) {
    _client =
        client ??
        VpnClient(
          onTunnelState: (state, detail, attemptId, {errorKey}) {
            _tunnelStateController.add(
              TunnelHostUpdate(
                state: state,
                detail: detail,
                attemptId: attemptId,
                errorKey: errorKey,
              ),
            );
          },
          onEngineLog: (level, source, tag, message, protocol) {
            _engineLogController.add(
              EngineLogMessage(
                timestamp: DateTime.now(),
                level: level,
                source: source,
                tag: tag,
                message: message,
                protocol: protocol,
              ),
            );
          },
          onProxyExposureChanged: (exposure) {
            _proxyExposureController.add(exposure);
          },
        );
  }

  late final VpnClient _client;
  final StreamController<TunnelHostUpdate> _tunnelStateController =
      StreamController<TunnelHostUpdate>.broadcast();
  final StreamController<EngineLogMessage> _engineLogController =
      StreamController<EngineLogMessage>.broadcast();
  final StreamController<ProxyExposure> _proxyExposureController =
      StreamController<ProxyExposure>.broadcast();

  @override
  Stream<EngineLogMessage> get engineLogs => _engineLogController.stream;

  @override
  Stream<ProxyExposure> get proxyExposures => _proxyExposureController.stream;

  @override
  Stream<TunnelHostUpdate> get tunnelStates => _tunnelStateController.stream;

  @override
  Future<TunnelRuntimeState> getRuntimeState() => _client.getRuntimeState();

  @override
  Future<BatteryOptimizationStatus> getBatteryOptimizationStatus() {
    return _client.getBatteryOptimizationStatus();
  }

  @override
  Future<void> connect(TunnelConnectRequest request) {
    return _client.connect(
      attemptId: request.attemptId,
      server: request.server,
      protocol: request.protocol,
      sstp: request.sstp,
      profileName: request.profileName,
      connectionMode: request.connectionMode,
      user: request.user,
      password: request.password,
      psk: request.psk,
      dnsAutomatic: request.dnsAutomatic,
      dnsServers: request.dnsServers,
      mtu: request.mtu,
      splitTunnelSettings: request.splitTunnelSettings,
      proxySettings: request.proxySettings,
    );
  }

  @override
  Future<void> connectGroup(TunnelGroupConnectRequest request) {
    return _client.connectGroup(
      groupId: request.groupId,
      attemptId: request.attemptId,
      proxySettings: request.proxySettings,
    );
  }

  @override
  Future<void> disconnect({
    required ConnectionMode connectionMode,
    String attemptId = '',
  }) {
    return _client.disconnect(
      connectionMode: connectionMode,
      attemptId: attemptId,
    );
  }

  @override
  void dispose() {
    _client.dispose();
    unawaited(_tunnelStateController.close());
    unawaited(_engineLogController.close());
    unawaited(_proxyExposureController.close());
  }

  @override
  Future<Uint8List?> getAppIcon(String packageName) {
    return _client.getAppIcon(packageName);
  }

  @override
  Future<List<CandidateApp>> listVpnCandidateApps() {
    return _client.listVpnCandidateApps();
  }

  @override
  Future<BatteryOptimizationRequestResult> openBatteryOptimizationSettings() {
    return _client.openBatteryOptimizationSettings();
  }

  @override
  Future<BatteryOptimizationRequestResult>
  openManufacturerBackgroundSettings() {
    return _client.openManufacturerBackgroundSettings();
  }

  @override
  Future<bool> prepareVpn() => _client.prepareVpn();

  @override
  Future<BatteryOptimizationRequestResult> requestIgnoreBatteryOptimizations() {
    return _client.requestIgnoreBatteryOptimizations();
  }

  @override
  Future<void> setLogLevel(LogDisplayLevel level) {
    return _client.setLogLevel(level);
  }
}

class ConnectivityRepositoryImpl implements ConnectivityRepository {
  ConnectivityRepositoryImpl(this._checker);

  final ConnectivityChecker _checker;

  @override
  Future<ConnectivityPingResult> ping(ConnectivityPingRequest request) =>
      _checker.ping(request);
}

class ProfileTransferRepositoryImpl implements ProfileTransferRepository {
  ProfileTransferRepositoryImpl({ProfileTransferBridge? bridge})
    : _bridge = bridge ?? ProfileTransferBridge();

  final ProfileTransferBridge _bridge;

  @override
  Stream<IncomingProfileTransfer> get incomingTransfers =>
      _bridge.incomingTransfers;

  @override
  Future<void> dispose() => _bridge.dispose();

  @override
  Future<List<IncomingProfileTransfer>> start() => _bridge.start();
}

class LogsRepositoryImpl implements LogsRepository {
  LogsRepositoryImpl({SharedPreferences? prefsOverride})
    : _prefsOverride = prefsOverride;

  static const String prefsKeyLogsJson = 'logs_entries_json_v1';
  static const int _maxLines = 10000;
  static const int _maxPersistedLines = 2000;

  final SharedPreferences? _prefsOverride;
  final List<LogEntry> _entries = <LogEntry>[];
  final StreamController<List<LogEntry>> _controller =
      StreamController<List<LogEntry>>.broadcast();
  bool _loaded = false;

  @override
  List<LogEntry> get entries => List<LogEntry>.unmodifiable(_entries);

  @override
  Stream<List<LogEntry>> get entriesStream => _controller.stream;

  @override
  Future<void> loadPersisted() async {
    if (_loaded) return;
    _loaded = true;
    final prefs = await _prefs();
    final raw = prefs.getString(prefsKeyLogsJson);
    if (raw == null || raw.trim().isEmpty) return;
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! List) return;
      final loaded = decoded
          .map(_entryFromJson)
          .whereType<LogEntry>()
          .toList(growable: false);
      if (loaded.isEmpty && _entries.isEmpty) return;
      final currentSessionEntries = List<LogEntry>.from(_entries);
      _entries
        ..clear()
        ..addAll((loaded + currentSessionEntries).takeLast(_maxLines));
      _controller.add(entries);
    } catch (_) {
      await prefs.remove(prefsKeyLogsJson);
    }
  }

  @override
  void append(LogEntry entry) {
    _entries.add(entry);
    if (_entries.length > _maxLines) {
      _entries.removeRange(0, _entries.length - _maxLines);
    }
    _controller.add(entries);
    unawaited(_persist());
  }

  @override
  void clear() {
    if (_entries.isEmpty) {
      unawaited(_clearPersisted());
      return;
    }
    _entries.clear();
    _controller.add(entries);
    unawaited(_clearPersisted());
  }

  Future<void> dispose() async {
    await _controller.close();
  }

  Future<SharedPreferences> _prefs() async =>
      _prefsOverride ?? await SharedPreferences.getInstance();

  Future<void> _persist() async {
    final prefs = await _prefs();
    final persisted = _entries
        .takeLast(_maxPersistedLines)
        .map(_entryToJson)
        .toList(growable: false);
    await prefs.setString(prefsKeyLogsJson, jsonEncode(persisted));
  }

  Future<void> _clearPersisted() async {
    final prefs = await _prefs();
    await prefs.remove(prefsKeyLogsJson);
  }

  Map<String, Object?> _entryToJson(LogEntry entry) => <String, Object?>{
    'timestamp': entry.timestamp.toIso8601String(),
    'level': entry.level.name,
    'source': entry.source.label,
    'tag': entry.tag,
    'message': entry.message,
  };

  LogEntry? _entryFromJson(Object? raw) {
    if (raw is! Map) return null;
    final timestamp = DateTime.tryParse(raw['timestamp']?.toString() ?? '');
    if (timestamp == null) return null;
    return LogEntry(
      timestamp: timestamp,
      level: _logLevelFromStorage(raw['level']),
      source: LogSource.parse(raw['source']?.toString()),
      tag: raw['tag']?.toString() ?? '',
      message: raw['message']?.toString() ?? '',
    );
  }

  LogLevel _logLevelFromStorage(Object? raw) {
    return switch (raw?.toString().trim().toLowerCase()) {
      'debug' => LogLevel.debug,
      'info' => LogLevel.info,
      'warning' || 'warn' => LogLevel.warning,
      'error' => LogLevel.error,
      _ => LogLevel.info,
    };
  }
}

extension _TakeLastExtension<T> on Iterable<T> {
  Iterable<T> takeLast(int count) {
    final list = toList(growable: false);
    if (list.length <= count) return list;
    return list.skip(list.length - count);
  }
}

class ProfileRepositoryException implements Exception {
  const ProfileRepositoryException(this.message);

  final String message;
}
