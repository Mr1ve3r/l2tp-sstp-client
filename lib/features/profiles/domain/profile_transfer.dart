import 'dart:convert';
import 'dart:typed_data';

import 'package:archive/archive.dart';

import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/data/profile_transfer_contract.dart';

/// A certificate travelling with the profile that selected it (SPEC 8.1.4).
class TransferredCertificate {
  const TransferredCertificate({
    required this.id,
    required this.alias,
    required this.pem,
  });

  /// The SHA-256 fingerprint, which is also the store's identifier for it.
  final String id;
  final String alias;

  /// The certificate itself. PEM is already base64 inside its own armour.
  final String pem;

  Map<String, Object?> toJson() => <String, Object?>{
    'id': id,
    'alias': alias,
    'pem': pem,
  };

  static TransferredCertificate? tryFromJson(Object? raw) {
    if (raw is! Map) return null;
    final id = raw['id'];
    final pem = raw['pem'];
    if (id is! String || pem is! String || id.isEmpty || pem.isEmpty) {
      return null;
    }
    return TransferredCertificate(
      id: id,
      alias: raw['alias'] as String? ?? '',
      pem: pem,
    );
  }
}

/// Which of a profile's secrets a transfer is allowed to carry.
///
/// A boolean was enough while every export either carried all three secrets or
/// none of them. A set shared across an organisation is neither: the pre-shared
/// key and the proxy credentials belong to everyone who is given the set, and
/// the VPN login and password belong to one person.
class TransferSecrets {
  const TransferSecrets({
    this.credentials = false,
    this.psk = false,
    this.proxy = false,
  });

  /// Settings only. What a file exported without a password may contain.
  static const TransferSecrets none = TransferSecrets();

  /// Everything, for a transfer that stays inside the application or goes
  /// into a container someone has put a password on.
  static const TransferSecrets all = TransferSecrets(
    credentials: true,
    psk: true,
    proxy: true,
  );

  /// What an organisation shares: its own secrets, none of anybody's own.
  static const TransferSecrets shared = TransferSecrets(psk: true, proxy: true);

  /// The VPN login and password, which are one person's rather than the site's.
  final bool credentials;

  final bool psk;

  /// The proxy login and password.
  final bool proxy;

  bool get any => credentials || psk || proxy;
}

/// One profile on its way in or out of the application (SPEC 8.1.4).
///
/// The profile is carried as the same map the store speaks, rather than
/// field by field: an export that spelled the profile a second way would be a
/// second thing to keep in step with the model, and it was already one field
/// behind by the time SSTP arrived.
///
/// Secrets are separate from the profile, and [toJson] leaves them out unless
/// asked. An export carrying them is written into the container
/// `ProfileStore.sealExport` makes, never as plain text.
class ProfileTransferEnvelope {
  const ProfileTransferEnvelope({
    required this.profile,
    this.password = '',
    this.psk = '',
    this.proxyPassword = '',
    this.certificates = const <TransferredCertificate>[],
    this.version = currentVersion,
  });

  /// Version 4 carries the whole profile map, every protocol, and certificates.
  static const int currentVersion = 4;

  /// Version 3 is what the build before phase 8 wrote: flat, L2TP-only.
  static const int legacyVersion = 3;

  static const String fileExtension = 'tfp';
  static const String mimeType = 'application/vnd.tunnelforge.profile+json';
  static const String uriScheme = 'tf';
  static const String uriHost = 'p';

  final int version;
  final Profile profile;
  final String password;
  final String psk;
  final String proxyPassword;
  final List<TransferredCertificate> certificates;

  bool get hasSecrets =>
      password.isNotEmpty || psk.isNotEmpty || proxyPassword.isNotEmpty;

  String get displayName => profile.displayName;
  String get server => profile.server;
  String get user => profile.user;

  /// The profile as it will be stored, under [id] and with a name of its own.
  Profile toProfile(String id) {
    final server = profile.server.trim();
    return profile.copyWith(
      id: id,
      displayName: profile.displayName.trim().isEmpty
          ? server
          : profile.displayName,
      server: server,
      mtu: Profile.normalizeMtu(profile.mtu),
      dns1Host: Profile.normalizeDnsServerForProtocol(
        profile.dns1Host,
        profile.dns1Protocol,
      ),
      dns2Host: Profile.normalizeDnsServerForProtocol(
        profile.dns2Host,
        profile.dns2Protocol,
      ),
      // The importing device has its own certificate store, so the ids in the
      // file mean nothing here until the certificates are imported and the
      // fingerprints come back the same. The caller does that and fills these in.
      trustedCertificateIds: const <String>[],
    );
  }

  /// @param secrets which secrets go in. Nothing by default: the file it
  ///   produces is the one that gets shared.
  /// @param includeConnectivityCheck whether the profile's own connectivity
  ///   check travels. It is settings rather than a secret, but it names a host
  ///   on the sender's network, and a recipient outside that network would get
  ///   a badge that reports the tunnel broken when it is not.
  Map<String, Object?> toJson({
    TransferSecrets secrets = TransferSecrets.none,
    bool includeConnectivityCheck = true,
  }) => <String, Object?>{
    'v': currentVersion,
    'profile': profileFor(
      secrets,
      includeConnectivityCheck: includeConnectivityCheck,
    ).toJson(),
    if (secrets.credentials) 'password': password,
    if (secrets.psk) 'psk': psk,
    if (secrets.proxy) 'proxyPassword': proxyPassword,
    'certificates': certificates
        .map((certificate) => certificate.toJson())
        .toList(),
  };

  /// The profile as [secrets] allows it to travel.
  ///
  /// The two logins are fields of the profile rather than secrets beside it, so
  /// leaving a flag off has to reach into the profile and clear them. Carrying
  /// a login onwards while its password stays behind would be the worst of
  /// both: it names the person the set was taken from and still cannot connect.
  Profile profileFor(
    TransferSecrets secrets, {
    bool includeConnectivityCheck = true,
  }) => profile.copyWith(
    user: secrets.credentials ? profile.user : '',
    proxyUsername: secrets.proxy ? profile.proxyUsername : '',
    connectivityCheckUrl: includeConnectivityCheck
        ? profile.connectivityCheckUrl
        : '',
    connectivityCheckTimeoutMs: includeConnectivityCheck
        ? profile.connectivityCheckTimeoutMs
        : 0,
  );

  /// Whether this profile names a connectivity check of its own, and so has
  /// something for the export form to offer.
  bool get hasConnectivityCheck =>
      profile.connectivityCheckUrl.trim().isNotEmpty ||
      profile.connectivityCheckTimeoutMs > 0;

  String toFileJson({
    TransferSecrets secrets = TransferSecrets.none,
    bool includeConnectivityCheck = true,
  }) => const JsonEncoder.withIndent('  ').convert(
    toJson(
      secrets: secrets,
      includeConnectivityCheck: includeConnectivityCheck,
    ),
  );

  /// A share link.
  ///
  /// It carries settings and nothing else. A link is pasted into whatever
  /// conversation is at hand and lives on in that history, and the payload is
  /// gzip and base64 rather than encryption, so anyone who ever sees the link
  /// can read every byte of it. Secrets travel in the sealed container instead,
  /// which is a file with a password on it. A link written by an older build
  /// still hands over its secrets on import; that history cannot be recalled,
  /// and refusing to read it would only strand the profile.
  String toTfUri() {
    final jsonBytes = utf8.encode(jsonEncode(toJson()));
    final compressed = GZipEncoder().encode(jsonBytes);
    final payload = base64UrlEncode(compressed).replaceAll('=', '');
    return '$uriScheme://$uriHost/$payload';
  }

  static ProfileTransferEnvelope fromProfile({
    required Profile profile,
    required String password,
    required String psk,
    String proxyPassword = '',
    List<TransferredCertificate> certificates =
        const <TransferredCertificate>[],
  }) {
    return ProfileTransferEnvelope(
      profile: profile,
      password: password,
      psk: psk,
      proxyPassword: proxyPassword,
      certificates: certificates,
    );
  }

  static ProfileTransferEnvelope fromIncomingTransfer(
    IncomingProfileTransfer transfer,
  ) {
    final data = transfer.data;
    if (data == null || data.isEmpty) {
      throw const FormatException('Incoming profile transfer is empty');
    }
    return switch (transfer.type) {
      ProfileTransferContract.typeTfpJson => fromFileJson(data),
      ProfileTransferContract.typeTfUri => fromTfUri(data),
      _ => throw FormatException(transfer.message ?? 'Unsupported transfer'),
    };
  }

  /// Base64 of the container's magic. Base64 encodes three bytes at a time,
  /// so the first six characters of a sealed file are fixed and the seventh
  /// is not; six is enough to tell a container from JSON.
  static const String sealedPrefix = 'VEZQQz';

  /// Whether [text] is an encrypted container rather than an exported profile.
  static bool looksSealed(String text) =>
      text.trimLeft().startsWith(sealedPrefix);

  static ProfileTransferEnvelope fromFileJson(String text) {
    if (looksSealed(text)) {
      throw const FormatException(
        'This profile file is encrypted. Open it with its password.',
      );
    }
    final decoded = jsonDecode(text);
    if (decoded is! Map) {
      throw const FormatException('Profile file must contain a JSON object');
    }
    return fromJsonMap(Map<String, Object?>.from(decoded));
  }

  static ProfileTransferEnvelope fromTfUri(String text) {
    final uri = Uri.tryParse(text.trim());
    if (uri == null || uri.scheme != uriScheme || uri.host != uriHost) {
      throw const FormatException('Invalid TunnelForge share link');
    }
    if (uri.pathSegments.isEmpty) {
      throw const FormatException('Share link payload is missing');
    }
    final payload = uri.pathSegments.join('');
    final normalized = base64Url.normalize(payload);
    final compressed = base64Url.decode(normalized);
    final jsonBytes = Uint8List.fromList(GZipDecoder().decodeBytes(compressed));
    return fromFileJson(utf8.decode(jsonBytes));
  }

  static ProfileTransferEnvelope fromJsonMap(Map<String, Object?> map) {
    final version = map['v'];
    if (version == legacyVersion) return _fromLegacyJsonMap(map);
    if (version != currentVersion) {
      throw FormatException('Unsupported profile transfer version: $version');
    }
    final raw = map['profile'];
    if (raw is! Map) {
      throw const FormatException('Profile transfer carries no profile');
    }
    final profile = Profile.tryFromJson(<String, Object?>{
      'id': 'imported',
      ...Map<String, Object?>.from(raw),
    });
    if (profile == null) {
      throw const FormatException('Profile transfer profile is incomplete');
    }
    _validate(profile);
    final certificates = <TransferredCertificate>[];
    for (final entry
        in (map['certificates'] as List<Object?>?) ?? const <Object?>[]) {
      final certificate = TransferredCertificate.tryFromJson(entry);
      if (certificate != null) certificates.add(certificate);
    }
    return ProfileTransferEnvelope(
      profile: profile,
      password: map['password'] as String? ?? '',
      psk: map['psk'] as String? ?? '',
      proxyPassword: map['proxyPassword'] as String? ?? '',
      certificates: certificates,
    );
  }

  /// A file written before phase 8: flat fields, L2TP, always with secrets.
  static ProfileTransferEnvelope _fromLegacyJsonMap(Map<String, Object?> map) {
    final profile = Profile.tryFromJson(<String, Object?>{
      'id': 'imported',
      'displayName': _requireString(map, 'displayName'),
      'server': _requireString(map, 'server'),
      'user': _requireString(map, 'user'),
      'dnsAutomatic': _requireBool(map, 'dnsAutomatic'),
      'dns1Host': _requireString(map, 'dns1Host'),
      'dns1Protocol': _requireString(map, 'dns1Protocol'),
      'dns2Host': _requireString(map, 'dns2Host'),
      'dns2Protocol': _requireString(map, 'dns2Protocol'),
      'mtu': _requireInt(map, 'mtu'),
    });
    if (profile == null) {
      throw const FormatException('Profile transfer profile is incomplete');
    }
    _validate(profile);
    return ProfileTransferEnvelope(
      version: legacyVersion,
      profile: profile,
      password: _requireString(map, 'password'),
      psk: _requireString(map, 'psk'),
    );
  }

  /// Refuses a profile that would be stored but could never connect.
  static void _validate(Profile profile) {
    if (profile.server.trim().isEmpty) {
      throw const FormatException('Profile transfer server is required');
    }
    for (final slot in <(String, String, DnsProtocol)>[
      ('DNS 1', profile.dns1Host, profile.dns1Protocol),
      ('DNS 2', profile.dns2Host, profile.dns2Protocol),
    ]) {
      if (Profile.invalidDnsServer(slot.$2, slot.$3) != null) {
        throw FormatException(
          Profile.validationMessageForDnsServer(slot.$1, slot.$2, slot.$3),
        );
      }
    }
    if (!profile.dnsAutomatic && profile.manualDnsServers.isEmpty) {
      throw const FormatException(
        'Manual DNS requires at least one DNS server',
      );
    }
  }

  static String exportFileNameFor(Profile profile) {
    final base = sanitizeFileName(
      profile.displayName.trim().isEmpty ? profile.server : profile.displayName,
    );
    final fallback = base.isEmpty ? 'tunnel-forge-profile' : base;
    return '$fallback.$fileExtension';
  }

  /// A name the user chose, reduced to something every file system accepts.
  static String sanitizeFileName(String value) {
    return value
        .trim()
        .replaceAll(RegExp(r'[\\/:*?"<>|]+'), '-')
        .replaceAll(RegExp(r'\s+'), '-')
        .replaceAll(RegExp(r'-+'), '-')
        .replaceAll(RegExp(r'^-+|-+$'), '')
        .toLowerCase();
  }

  static int _requireInt(Map<String, Object?> map, String key) {
    final value = map[key];
    return switch (value) {
      int v => v,
      num v => v.toInt(),
      String v =>
        int.tryParse(v.trim()) ??
            (throw FormatException('Invalid integer field: $key')),
      _ => throw FormatException('Missing integer field: $key'),
    };
  }

  static bool _requireBool(Map<String, Object?> map, String key) {
    final value = map[key];
    if (value is! bool) {
      throw FormatException('Missing bool field: $key');
    }
    return value;
  }

  static String _requireString(Map<String, Object?> map, String key) {
    final value = map[key];
    if (value is! String) {
      throw FormatException('Missing string field: $key');
    }
    return value;
  }
}

class IncomingProfileTransfer {
  const IncomingProfileTransfer({
    required this.type,
    this.data,
    this.message,
    this.source,
  });

  final String type;
  final String? data;
  final String? message;
  final String? source;

  bool get isError => type == ProfileTransferContract.typeError;

  static IncomingProfileTransfer? tryFromMap(Object? raw) {
    if (raw is! Map) return null;
    final map = Map<String, Object?>.from(raw);
    final type = map[ProfileTransferContract.argType];
    if (type is! String || type.isEmpty) return null;
    return IncomingProfileTransfer(
      type: type,
      data: map[ProfileTransferContract.argData] as String?,
      message: map[ProfileTransferContract.argMessage] as String?,
      source: map[ProfileTransferContract.argSource] as String?,
    );
  }
}
