import 'dart:convert';

import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_transfer.dart';

/// A set of profiles handed out as one file.
///
/// The case this exists for is an organisation with several sites: six profiles
/// across two protocols, the same pre-shared key and the same certificates
/// behind them, given to every employee. Sending six files and a page of
/// instructions is how that goes wrong, so the set travels as one.
///
/// A set is composed of [ProfileTransferEnvelope] rather than being a widened
/// one. The envelope is built around a single profile — [toProfile],
/// [ProfileTransferEnvelope.hasSecrets], [ProfileTransferEnvelope.displayName]
/// — and a list inside it would leave each of those either lying or asking
/// which profile was meant. Holding the envelopes instead reuses their
/// validation, their certificate handling and their one spelling of a profile.
class ProfileBundle {
  const ProfileBundle({
    required this.name,
    required this.entries,
    this.createdAt,
  });

  /// Version 5 is the set. Version 4 and below are one profile (SPEC 8.1.4).
  static const int version = 5;

  /// Distinguishes a set from a profile inside the same `.tfp` extension.
  static const String kind = 'bundle';

  /// What the set is called, which is also what its file is named after.
  final String name;

  final List<ProfileTransferEnvelope> entries;

  /// When the set was written, so a recipient holding two can tell which is
  /// the newer. Absent in a set written by something that did not record it.
  final DateTime? createdAt;

  int get length => entries.length;

  bool get isEmpty => entries.isEmpty;

  /// Every certificate in the set, once each.
  ///
  /// Six profiles behind one corporate CA name the same certificate six times.
  /// The pool is what keeps the file from carrying six copies of it; each entry
  /// keeps only the identifiers, and reading the set puts them back.
  List<TransferredCertificate> get certificatePool {
    final pool = <String, TransferredCertificate>{};
    for (final entry in entries) {
      for (final certificate in entry.certificates) {
        pool.putIfAbsent(certificate.id, () => certificate);
      }
    }
    return pool.values.toList(growable: false);
  }

  Map<String, Object?> toJson({
    TransferSecrets secrets = TransferSecrets.shared,
  }) => <String, Object?>{
    'v': version,
    'kind': kind,
    'name': name,
    if (createdAt != null) 'createdAt': createdAt!.toUtc().toIso8601String(),
    'entries': entries
        .map((entry) => _entryJson(entry, secrets))
        .toList(growable: false),
    'certificates': certificatePool
        .map((certificate) => certificate.toJson())
        .toList(growable: false),
  };

  String toFileJson({TransferSecrets secrets = TransferSecrets.shared}) =>
      const JsonEncoder.withIndent('  ').convert(toJson(secrets: secrets));

  /// One entry, spelled the way the envelope spells itself minus what the set
  /// says once for everybody: the version, and the certificates themselves.
  static Map<String, Object?> _entryJson(
    ProfileTransferEnvelope entry,
    TransferSecrets secrets,
  ) {
    final map = entry.toJson(secrets: secrets);
    map.remove('v');
    map.remove('certificates');
    map['certificateIds'] = entry.certificates
        .map((certificate) => certificate.id)
        .toList(growable: false);
    return map;
  }

  static bool looksLikeBundle(Map<String, Object?> map) => map['v'] == version;

  static ProfileBundle fromJsonMap(Map<String, Object?> map) {
    if (!looksLikeBundle(map)) {
      throw FormatException('Unsupported profile set version: ${map['v']}');
    }
    final pool = <String, TransferredCertificate>{};
    for (final raw
        in (map['certificates'] as List<Object?>?) ?? const <Object?>[]) {
      final certificate = TransferredCertificate.tryFromJson(raw);
      if (certificate != null) pool[certificate.id] = certificate;
    }
    final rawEntries = map['entries'];
    if (rawEntries is! List || rawEntries.isEmpty) {
      throw const FormatException('Profile set carries no profiles');
    }
    final entries = <ProfileTransferEnvelope>[];
    for (final raw in rawEntries) {
      if (raw is! Map) {
        throw const FormatException('Profile set entry is not an object');
      }
      entries.add(_entryFromJson(Map<String, Object?>.from(raw), pool));
    }
    final createdAt = map['createdAt'];
    return ProfileBundle(
      name: (map['name'] as String?)?.trim() ?? '',
      entries: entries,
      createdAt: createdAt is String ? DateTime.tryParse(createdAt) : null,
    );
  }

  /// One entry, read back through the envelope so that a set and a single
  /// profile are validated by the same code rather than by two versions of it.
  static ProfileTransferEnvelope _entryFromJson(
    Map<String, Object?> map,
    Map<String, TransferredCertificate> pool,
  ) {
    final certificates = <Object?>[];
    for (final id
        in (map['certificateIds'] as List<Object?>?) ?? const <Object?>[]) {
      // An identifier with nothing behind it is dropped rather than refused:
      // the profile is still worth having, and the certificate it wanted will
      // be reported missing by the trust store when it is asked to connect.
      final certificate = pool[id];
      if (certificate != null) certificates.add(certificate.toJson());
    }
    return ProfileTransferEnvelope.fromJsonMap(<String, Object?>{
      ...map,
      'v': ProfileTransferEnvelope.currentVersion,
      'certificates': certificates,
    });
  }

  static String exportFileNameFor(String bundleName) {
    final base = ProfileTransferEnvelope.sanitizeFileName(bundleName);
    final fallback = base.isEmpty ? 'tunnel-forge-profiles' : base;
    return '$fallback.${ProfileTransferEnvelope.fileExtension}';
  }
}

/// What was in a `.tfp` file: one profile, or a set of them.
///
/// Both extensions and both MIME types would have been a second thing to teach
/// the manifest, the file picker and the intent handler, and a sealed container
/// is opaque from the outside anyway — the extension could not have told them
/// apart. The version inside the JSON does.
sealed class ProfileTransferDocument {
  const ProfileTransferDocument();

  /// Reads either shape, refusing a container that has not been opened yet.
  static ProfileTransferDocument parse(String text) {
    if (ProfileTransferEnvelope.looksSealed(text)) {
      throw const FormatException(
        'This profile file is encrypted. Open it with its password.',
      );
    }
    final decoded = jsonDecode(text);
    if (decoded is! Map) {
      throw const FormatException('Profile file must contain a JSON object');
    }
    final map = Map<String, Object?>.from(decoded);
    if (ProfileBundle.looksLikeBundle(map)) {
      return ProfileSetDocument(ProfileBundle.fromJsonMap(map));
    }
    return SingleProfileDocument(ProfileTransferEnvelope.fromJsonMap(map));
  }
}

final class SingleProfileDocument extends ProfileTransferDocument {
  const SingleProfileDocument(this.envelope);

  final ProfileTransferEnvelope envelope;
}

final class ProfileSetDocument extends ProfileTransferDocument {
  const ProfileSetDocument(this.bundle);

  final ProfileBundle bundle;
}

/// What to do with one entry of a set being imported.
enum BundleImportAction {
  /// Store it as a profile of its own, even if one like it is already here.
  add,

  /// Overwrite a profile already here, keeping its identity and its login.
  replace,

  skip,
}

/// The decision made about one entry, by index into [ProfileBundle.entries].
class BundleImportChoice {
  const BundleImportChoice({
    required this.entryIndex,
    required this.action,
    this.targetProfileId,
  });

  final int entryIndex;
  final BundleImportAction action;

  /// Which profile [BundleImportAction.replace] overwrites. Ignored otherwise.
  final String? targetProfileId;
}

class BundleImportResult {
  const BundleImportResult({
    this.added = 0,
    this.replaced = 0,
    this.skipped = 0,
    this.firstImportedId,
  });

  final int added;
  final int replaced;
  final int skipped;

  /// What to select afterwards, so importing a set leaves something chosen.
  final String? firstImportedId;

  int get stored => added + replaced;
}

/// Finds the profile an entry would overwrite.
///
/// A set is handed out again whenever the organisation changes something, so
/// the same six profiles arrive repeatedly. Matching on where a profile
/// connects and what it is called is what keeps the third handout from leaving
/// eighteen rows behind. The login is not part of the match: it is exactly the
/// field the recipient filled in and the set left empty.
Profile? matchingProfileFor(
  ProfileTransferEnvelope entry,
  Iterable<Profile> existing,
) {
  final server = entry.profile.server.trim().toLowerCase();
  final name = entry.profile.displayName.trim().toLowerCase();
  for (final profile in existing) {
    if (profile.protocol == entry.profile.protocol &&
        profile.port == entry.profile.port &&
        profile.server.trim().toLowerCase() == server &&
        profile.displayName.trim().toLowerCase() == name) {
      return profile;
    }
  }
  return null;
}
