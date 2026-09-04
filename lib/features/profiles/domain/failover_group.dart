// A failover group as Flutter sees it (SPEC 10.1). The stored copy lives in
// Kotlin beside the profiles; this is what crosses the profile method channel.
import 'package:equatable/equatable.dart';

/// An ordered set of profiles tried one after another until one comes up.
///
/// The members are profile ids rather than whole profiles. The host sends them
/// that way on purpose: the caller already holds the profile list, and carrying
/// each member twice would make two copies of one profile that could disagree
/// after an edit. Resolving an id that no longer names a profile is therefore
/// this side's job — see [resolveMembers].
class FailoverGroup extends Equatable {
  const FailoverGroup({
    required this.id,
    required this.name,
    this.connectTimeoutSec = defaultConnectTimeoutSec,
    this.createdAt = 0,
    this.memberIds = const <String>[],
  });

  final String id;
  final String name;

  /// How long one member may take before the group gives up on it and tries
  /// the next (SPEC 10.1.2).
  final int connectTimeoutSec;

  /// Milliseconds since the epoch, so the list keeps the order groups were
  /// made in. Zero on a group that has not been saved yet; the host fills it.
  final int createdAt;

  /// Profile ids, in the order the group tries them.
  final List<String> memberIds;

  /// SPEC 10.1.2's default budget for one member.
  static const int defaultConnectTimeoutSec = 15;

  /// The range a budget may take. Mirrors `FailoverGroup.kt`: below the floor a
  /// member is abandoned before a slow network has answered at all, and above
  /// the ceiling a group of three is a longer wait than anyone will sit
  /// through. The host clamps too — this copy is here so the editor can say no
  /// before the round trip rather than silently changing what was typed.
  static const int minConnectTimeoutSec = 5;
  static const int maxConnectTimeoutSec = 120;

  /// [seconds] if it is a budget this application will run, the default
  /// otherwise.
  static int normalizeTimeout(int seconds) {
    return seconds >= minConnectTimeoutSec && seconds <= maxConnectTimeoutSec
        ? seconds
        : defaultConnectTimeoutSec;
  }

  /// The name to show. A group is never saved without one, but a group read
  /// from an older store might still arrive empty.
  String get displayName => name.trim().isEmpty ? id : name.trim();

  bool get isEmpty => memberIds.isEmpty;

  FailoverGroup copyWith({
    String? id,
    String? name,
    int? connectTimeoutSec,
    int? createdAt,
    List<String>? memberIds,
  }) {
    return FailoverGroup(
      id: id ?? this.id,
      name: name ?? this.name,
      connectTimeoutSec: connectTimeoutSec ?? this.connectTimeoutSec,
      createdAt: createdAt ?? this.createdAt,
      memberIds: memberIds ?? this.memberIds,
    );
  }

  Map<String, Object?> toJson() => <String, Object?>{
    'id': id,
    'name': name,
    'connectTimeoutSec': connectTimeoutSec,
    'createdAt': createdAt,
    'memberIds': memberIds,
  };

  /// The group in [raw], or null if it is not one.
  ///
  /// A row without an id is dropped rather than repaired: it cannot be saved
  /// back, deleted, or connected, so showing it would only offer actions that
  /// all fail.
  static FailoverGroup? tryFromJson(Object? raw) {
    if (raw is! Map) return null;
    final id = (raw['id'] as String?)?.trim() ?? '';
    if (id.isEmpty) return null;
    final members = <String>[];
    final rawMembers = raw['memberIds'];
    if (rawMembers is List) {
      for (final entry in rawMembers) {
        final memberId = (entry as String?)?.trim() ?? '';
        if (memberId.isNotEmpty && !members.contains(memberId)) {
          members.add(memberId);
        }
      }
    }
    return FailoverGroup(
      id: id,
      name: (raw['name'] as String?)?.trim() ?? '',
      connectTimeoutSec: normalizeTimeout(
        (raw['connectTimeoutSec'] as num?)?.toInt() ?? defaultConnectTimeoutSec,
      ),
      createdAt: (raw['createdAt'] as num?)?.toInt() ?? 0,
      memberIds: members,
    );
  }

  /// The members of this group as rows of [profiles], in the group's order.
  ///
  /// Ids that name nothing are skipped. A profile can be deleted while a group
  /// still mentions it — the host's foreign key removes the membership row, but
  /// a list already in memory has not heard about it yet — and the shorter list
  /// is the honest answer.
  List<T> resolveMembers<T>(
    Iterable<T> profiles,
    String Function(T profile) idOf,
  ) {
    final byId = <String, T>{
      for (final profile in profiles) idOf(profile): profile,
    };
    final out = <T>[];
    for (final memberId in memberIds) {
      final profile = byId[memberId];
      if (profile != null) out.add(profile);
    }
    return out;
  }

  @override
  List<Object?> get props => [
    id,
    name,
    connectTimeoutSec,
    createdAt,
    memberIds,
  ];
}
