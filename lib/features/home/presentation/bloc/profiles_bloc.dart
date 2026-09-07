import 'dart:async';

import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import 'package:tunnel_forge/l10n/app_localizations.dart';
import 'package:tunnel_forge/features/profiles/domain/failover_group.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_bundle.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_transfer.dart';
import 'package:tunnel_forge/features/profiles/data/profile_transfer_contract.dart';
import '../../../home/data/home_repositories_impl.dart';
import '../../../home/domain/home_models.dart';
import '../../../home/domain/home_repositories.dart';

sealed class ProfilesEvent extends Equatable {
  const ProfilesEvent();

  @override
  List<Object?> get props => const [];
}

final class ProfilesStarted extends ProfilesEvent {
  const ProfilesStarted();
}

final class ProfilesSelectionChanged extends ProfilesEvent {
  const ProfilesSelectionChanged(this.id);

  final String? id;

  @override
  List<Object?> get props => [id];
}

/// Chooses a failover group as what the connect button starts, or, with a null
/// id, chooses nothing (SPEC 10.1.3).
///
/// A group and a profile are alternatives, not a pair: picking one drops the
/// other, because the button starts exactly one thing.
final class ProfilesGroupSelectionChanged extends ProfilesEvent {
  const ProfilesGroupSelectionChanged(this.id);

  final String? id;

  @override
  List<Object?> get props => [id];
}

/// Stores a group. A group with an empty id is a new one.
final class ProfilesGroupSaveRequested extends ProfilesEvent {
  const ProfilesGroupSaveRequested(this.group);

  final FailoverGroup group;

  @override
  List<Object?> get props => [group];
}

final class ProfilesGroupDeleteRequested extends ProfilesEvent {
  const ProfilesGroupDeleteRequested(this.id);

  final String id;

  @override
  List<Object?> get props => [id];
}

final class ProfilesImportRequested extends ProfilesEvent {
  const ProfilesImportRequested(this.request);

  final ImportTransferRequest request;

  @override
  List<Object?> get props => [request];
}

final class ProfilesDeleteRequested extends ProfilesEvent {
  const ProfilesDeleteRequested(this.id);

  final String id;

  @override
  List<Object?> get props => [id];
}

final class ProfilesRefreshRequested extends ProfilesEvent {
  const ProfilesRefreshRequested({this.preferredActiveId});

  final String? preferredActiveId;

  @override
  List<Object?> get props => [preferredActiveId];
}

final class ProfilesCopyShareLinkRequested extends ProfilesEvent {
  const ProfilesCopyShareLinkRequested(this.id);

  final String id;

  @override
  List<Object?> get props => [id];
}

final class ProfilesExportFileRequested extends ProfilesEvent {
  const ProfilesExportFileRequested(this.id, {this.password});

  final String id;

  /// Seals the file and puts the secrets in it. Without one the file carries
  /// settings only, which is what it did before a password could be given.
  final String? password;

  @override
  List<Object?> get props => [id, password];
}

/// Writes several profiles out as one sealed set.
final class ProfilesExportBundleRequested extends ProfilesEvent {
  const ProfilesExportBundleRequested({
    required this.profileIds,
    required this.bundleName,
    required this.password,
  });

  final List<String> profileIds;
  final String bundleName;
  final String password;

  @override
  List<Object?> get props => [profileIds, bundleName, password];
}

/// Fills in the login and password of a profile that arrived without them.
final class ProfilesCredentialsSubmitted extends ProfilesEvent {
  const ProfilesCredentialsSubmitted({
    required this.id,
    required this.user,
    required this.password,
  });

  final String id;
  final String user;
  final String password;

  @override
  List<Object?> get props => [id, user, password];
}

/// Says the sealed file waiting in the state has been dealt with, opened or
/// not, so the next one is noticed.
final class ProfilesSealedTransferHandled extends ProfilesEvent {
  const ProfilesSealedTransferHandled();
}

/// Stores the entries of an opened set that [choices] asks for.
final class ProfilesBundleImportRequested extends ProfilesEvent {
  const ProfilesBundleImportRequested({
    required this.bundle,
    required this.choices,
  });

  final ProfileBundle bundle;
  final List<BundleImportChoice> choices;

  @override
  List<Object?> get props => [bundle, choices];
}

final class ProfilesImportSelectionPolicyChanged extends ProfilesEvent {
  const ProfilesImportSelectionPolicyChanged(
    this.selectImportedProfileWhenIdle,
  );

  final bool selectImportedProfileWhenIdle;

  @override
  List<Object?> get props => [selectImportedProfileWhenIdle];
}

class ProfilesState extends Equatable {
  const ProfilesState({
    this.loading = true,
    this.profiles = const <Profile>[],
    this.activeProfileId,
    this.activeProfileRow,
    this.groups = const <FailoverGroup>[],
    this.activeGroupId,
    this.savedGroupId,
    this.selectImportedProfileWhenIdle = true,
    this.profilesAwaitingCredentials = const <String>{},
    this.pendingSealedTransfer,
    this.message,
  });

  final bool loading;
  final List<Profile> profiles;
  final String? activeProfileId;
  final ProfileSecretRow? activeProfileRow;

  /// The failover groups, oldest first (SPEC 10.1.1).
  final List<FailoverGroup> groups;

  /// The group the connect button would start, or null when a profile is what
  /// it would start. Never set at the same time as [activeProfileId].
  final String? activeGroupId;

  /// The group the last successful save stored, so the editor can tell its own
  /// save from someone else's refresh. Cleared on every other change.
  final String? savedGroupId;

  final bool selectImportedProfileWhenIdle;

  /// Profiles that arrived in a shared set and still have no login of their
  /// own. Marked at import rather than guessed from an empty username, which
  /// an ordinary profile is also allowed to have.
  final Set<String> profilesAwaitingCredentials;

  /// An encrypted file waiting for its password to be asked for.
  final SealedTransfer? pendingSealedTransfer;

  bool awaitsCredentials(String? id) =>
      id != null && profilesAwaitingCredentials.contains(id);

  final HomeMessage? message;

  bool get hasActiveProfile {
    final activeId = activeProfileId;
    return activeId != null &&
        profiles.any((profile) => profile.id == activeId);
  }

  /// The chosen group, or null if none is chosen or it has been deleted.
  FailoverGroup? get activeGroup {
    final activeId = activeGroupId;
    if (activeId == null) return null;
    for (final group in groups) {
      if (group.id == activeId) return group;
    }
    return null;
  }

  /// Whether the connect button has a group it can actually start.
  ///
  /// An empty group is not one. The host refuses it — "the failover group X has
  /// no profiles in it" — and refusing here as well means the refusal arrives
  /// before the VPN permission dialog rather than after it.
  bool get hasActiveGroup {
    final group = activeGroup;
    return group != null && !group.isEmpty;
  }

  /// The profiles of [activeGroup], in the order it tries them.
  List<Profile> get activeGroupMembers =>
      activeGroup?.resolveMembers(profiles, (profile) => profile.id) ??
      const <Profile>[];

  ProfilesState copyWith({
    bool? loading,
    List<Profile>? profiles,
    String? activeProfileId,
    bool clearActiveProfileId = false,
    ProfileSecretRow? activeProfileRow,
    bool clearActiveProfileRow = false,
    List<FailoverGroup>? groups,
    String? activeGroupId,
    bool clearActiveGroupId = false,
    String? savedGroupId,
    bool? selectImportedProfileWhenIdle,
    Set<String>? profilesAwaitingCredentials,
    SealedTransfer? pendingSealedTransfer,
    bool clearPendingSealedTransfer = false,
    HomeMessage? message,
    bool clearMessage = false,
  }) {
    return ProfilesState(
      loading: loading ?? this.loading,
      profiles: profiles ?? this.profiles,
      activeProfileId: clearActiveProfileId
          ? null
          : (activeProfileId ?? this.activeProfileId),
      activeProfileRow: clearActiveProfileRow
          ? null
          : (activeProfileRow ?? this.activeProfileRow),
      groups: groups ?? this.groups,
      activeGroupId: clearActiveGroupId
          ? null
          : (activeGroupId ?? this.activeGroupId),
      // Not carried forward: it marks one save, and a state that repeated it
      // would make the next refresh look like a second save of the same group.
      savedGroupId: savedGroupId,
      selectImportedProfileWhenIdle:
          selectImportedProfileWhenIdle ?? this.selectImportedProfileWhenIdle,
      profilesAwaitingCredentials:
          profilesAwaitingCredentials ?? this.profilesAwaitingCredentials,
      pendingSealedTransfer: clearPendingSealedTransfer
          ? null
          : (pendingSealedTransfer ?? this.pendingSealedTransfer),
      message: clearMessage ? null : (message ?? this.message),
    );
  }

  @override
  List<Object?> get props => [
    loading,
    profiles,
    activeProfileId,
    activeProfileRow,
    groups,
    activeGroupId,
    savedGroupId,
    selectImportedProfileWhenIdle,
    profilesAwaitingCredentials,
    pendingSealedTransfer,
    message,
  ];
}

/// A `.tfp` that arrived encrypted and is waiting for someone to be asked.
///
/// A bloc cannot put a password dialog on screen, and the file most often
/// arrives by a tap on an attachment rather than through the import menu, so
/// the sealed payload waits in the state until a widget picks it up.
class SealedTransfer extends Equatable {
  const SealedTransfer({required this.payload, required this.source, required this.id});

  final String payload;
  final String source;

  /// Distinguishes two files that happen to be identical, so the second one
  /// still reaches the screen.
  final int id;

  @override
  List<Object?> get props => [payload, source, id];
}

class ProfilesBloc extends Bloc<ProfilesEvent, ProfilesState> {
  ProfilesBloc(this._profilesRepository, this._transferRepository)
    : super(const ProfilesState()) {
    on<ProfilesStarted>(_onStarted);
    on<ProfilesSelectionChanged>(_onSelectionChanged);
    on<ProfilesGroupSelectionChanged>(_onGroupSelectionChanged);
    on<ProfilesGroupSaveRequested>(_onGroupSaveRequested);
    on<ProfilesGroupDeleteRequested>(_onGroupDeleteRequested);
    on<ProfilesImportRequested>(_onImportRequested);
    on<ProfilesDeleteRequested>(_onDeleteRequested);
    on<ProfilesRefreshRequested>(_onRefreshRequested);
    on<ProfilesCopyShareLinkRequested>(_onCopyShareLinkRequested);
    on<ProfilesExportFileRequested>(_onExportFileRequested);
    on<ProfilesExportBundleRequested>(_onExportBundleRequested);
    on<ProfilesBundleImportRequested>(_onBundleImportRequested);
    on<ProfilesCredentialsSubmitted>(_onCredentialsSubmitted);
    on<ProfilesSealedTransferHandled>(
      (event, emit) =>
          emit(state.copyWith(clearPendingSealedTransfer: true)),
    );
    on<ProfilesImportSelectionPolicyChanged>(_onImportSelectionPolicyChanged);
  }

  final ProfilesRepository _profilesRepository;
  final ProfileTransferRepository _transferRepository;
  StreamSubscription<IncomingProfileTransfer>? _transferSub;
  int _messageId = 0;

  Future<void> _onStarted(
    ProfilesStarted event,
    Emitter<ProfilesState> emit,
  ) async {
    final lastGroupId = await _profilesRepository.loadLastGroupId();
    await _reloadProfiles(
      emit,
      // A group and a profile are alternatives: whichever was chosen last is
      // the one that was stored, and the other was cleared at the same time.
      preferredActiveId: lastGroupId == null
          ? await _profilesRepository.loadLastProfileId()
          : null,
      preferredActiveGroupId: lastGroupId,
    );
    await _transferSub?.cancel();
    _transferSub = _transferRepository.incomingTransfers.listen(
      _queueIncomingTransfer,
    );
    final pendingTransfers = await _transferRepository.start();
    for (final transfer in pendingTransfers) {
      _queueIncomingTransfer(transfer);
    }
  }

  Future<void> _onSelectionChanged(
    ProfilesSelectionChanged event,
    Emitter<ProfilesState> emit,
  ) async {
    if (event.id == null) {
      await _profilesRepository.setLastProfileId(null);
      await _profilesRepository.setLastGroupId(null);
      emit(
        state.copyWith(
          clearActiveProfileId: true,
          clearActiveProfileRow: true,
          clearActiveGroupId: true,
        ),
      );
      return;
    }
    final row = await _profilesRepository.loadProfileWithSecrets(event.id!);
    if (row == null) {
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.current.profileNoLongerExists,
            error: true,
          ),
        ),
      );
      return;
    }
    await _profilesRepository.setLastProfileId(event.id);
    await _profilesRepository.setLastGroupId(null);
    emit(
      state.copyWith(
        activeProfileId: event.id,
        activeProfileRow: row,
        clearActiveGroupId: true,
      ),
    );
  }

  Future<void> _onGroupSelectionChanged(
    ProfilesGroupSelectionChanged event,
    Emitter<ProfilesState> emit,
  ) async {
    final id = event.id;
    if (id == null) {
      await _profilesRepository.setLastGroupId(null);
      emit(state.copyWith(clearActiveGroupId: true));
      return;
    }
    if (!state.groups.any((group) => group.id == id)) {
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.current.failoverGroupNoLongerExists,
            error: true,
          ),
        ),
      );
      return;
    }
    // The profile goes with it, on both sides: the button starts one thing, and
    // leaving a last-profile id behind would make a restart pick the profile
    // back up.
    await _profilesRepository.setLastProfileId(null);
    await _profilesRepository.setLastGroupId(id);
    emit(
      state.copyWith(
        activeGroupId: id,
        clearActiveProfileId: true,
        clearActiveProfileRow: true,
      ),
    );
  }

  Future<void> _onGroupSaveRequested(
    ProfilesGroupSaveRequested event,
    Emitter<ProfilesState> emit,
  ) async {
    if (event.group.name.trim().isEmpty) {
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.current.failoverGroupNeedsName,
            error: true,
          ),
        ),
      );
      return;
    }
    try {
      final stored = await _profilesRepository.saveFailoverGroup(event.group);
      final groups = await _profilesRepository.loadFailoverGroups();
      emit(
        state.copyWith(
          groups: groups,
          savedGroupId: stored.id,
          message: _nextMessage(AppText.current.failoverGroupSaved),
        ),
      );
    } catch (_) {
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.current.couldNotSaveFailoverGroup,
            error: true,
          ),
        ),
      );
    }
  }

  Future<void> _onGroupDeleteRequested(
    ProfilesGroupDeleteRequested event,
    Emitter<ProfilesState> emit,
  ) async {
    try {
      await _profilesRepository.deleteFailoverGroup(event.id);
      final groups = await _profilesRepository.loadFailoverGroups();
      final wasActive = state.activeGroupId == event.id;
      if (wasActive) await _profilesRepository.setLastGroupId(null);
      emit(
        state.copyWith(
          groups: groups,
          clearActiveGroupId: wasActive,
          message: _nextMessage(AppText.current.failoverGroupRemoved),
        ),
      );
    } catch (_) {
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.current.couldNotDeleteFailoverGroup,
            error: true,
          ),
        ),
      );
    }
  }

  Future<void> _onImportRequested(
    ProfilesImportRequested event,
    Emitter<ProfilesState> emit,
  ) async {
    final transfer = event.request.transfer;
    if (transfer.isError) {
      emit(
        state.copyWith(
          message: _nextMessage(
            transfer.message ?? AppText.current.couldNotOpenIncomingProfile,
            error: true,
          ),
        ),
      );
      return;
    }
    final data = transfer.data;
    if (transfer.type == ProfileTransferContract.typeTfpJson &&
        data != null &&
        ProfileTransferEnvelope.looksSealed(data)) {
      // Nothing can be read out of it here. It waits for a widget to ask for
      // the password rather than being refused, which is what a file tapped in
      // a messenger used to get.
      emit(
        state.copyWith(
          pendingSealedTransfer: SealedTransfer(
            payload: data,
            source: transfer.source ?? '',
            id: ++_messageId,
          ),
        ),
      );
      return;
    }
    try {
      final envelope = ProfileTransferEnvelope.fromIncomingTransfer(transfer);
      final imported = await _profilesRepository.saveImportedProfile(
        envelope,
        selectAsLastProfile: event.request.selectAsLastProfile,
      );
      await _reloadProfiles(
        emit,
        preferredActiveId: event.request.selectAsLastProfile
            ? imported.id
            : null,
      );
      final source = switch ((transfer.source, transfer.type)) {
        ('Clipboard', ProfileTransferContract.typeTfUri) =>
          AppText.current.sourceClipboardShareLink,
        ('Clipboard', _) => AppText.current.sourceClipboard,
        (_, ProfileTransferContract.typeTfUri) =>
          AppText.current.sourceShareLink,
        _ => AppText.current.sourceTfpFile,
      };
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.current.importedProfileFromSource(
              imported.displayName,
              source,
            ),
          ),
        ),
      );
    } on FormatException catch (error) {
      emit(state.copyWith(message: _nextMessage(error.message, error: true)));
    } catch (_) {
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.current.couldNotImportProfile,
            error: true,
          ),
        ),
      );
    }
  }

  Future<void> _onDeleteRequested(
    ProfilesDeleteRequested event,
    Emitter<ProfilesState> emit,
  ) async {
    try {
      await _profilesRepository.deleteProfile(event.id);
      final shouldSelectFallback = state.activeProfileId == event.id;
      final nextId = shouldSelectFallback ? null : state.activeProfileId;
      await _reloadProfiles(emit, preferredActiveId: nextId);
      if (shouldSelectFallback &&
          state.activeProfileId == null &&
          state.profiles.isNotEmpty) {
        add(ProfilesSelectionChanged(state.profiles.first.id));
      }
      emit(
        state.copyWith(message: _nextMessage(AppText.current.profileRemoved)),
      );
    } catch (_) {
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.current.couldNotDeleteProfile,
            error: true,
          ),
        ),
      );
    }
  }

  Future<void> _onRefreshRequested(
    ProfilesRefreshRequested event,
    Emitter<ProfilesState> emit,
  ) async {
    await _reloadProfiles(
      emit,
      preferredActiveId: event.preferredActiveId ?? state.activeProfileId,
    );
  }

  Future<void> _onCopyShareLinkRequested(
    ProfilesCopyShareLinkRequested event,
    Emitter<ProfilesState> emit,
  ) async {
    try {
      await _profilesRepository.copyProfileShareLink(event.id);
      emit(
        state.copyWith(message: _nextMessage(AppText.current.shareLinkCopied)),
      );
    } on ProfileRepositoryException catch (error) {
      emit(state.copyWith(message: _nextMessage(error.message, error: true)));
    } catch (_) {
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.current.couldNotCopyShareLink,
            error: true,
          ),
        ),
      );
    }
  }

  Future<void> _onExportFileRequested(
    ProfilesExportFileRequested event,
    Emitter<ProfilesState> emit,
  ) async {
    try {
      await _profilesRepository.exportProfileFile(
        event.id,
        password: event.password,
      );
      emit(
        state.copyWith(message: _nextMessage(AppText.current.profileFileReady)),
      );
    } on ProfileRepositoryException catch (error) {
      emit(state.copyWith(message: _nextMessage(error.message, error: true)));
    } catch (_) {
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.current.couldNotExportTfpFile,
            error: true,
          ),
        ),
      );
    }
  }

  /// Stores the credentials a profile from a shared set was missing.
  ///
  /// The pre-shared key, the proxy password and everything else are read back
  /// and written again untouched: this event knows about two fields, and a save
  /// that passed empty strings for the rest would clear the very secrets the
  /// set was sent to deliver.
  Future<void> _onCredentialsSubmitted(
    ProfilesCredentialsSubmitted event,
    Emitter<ProfilesState> emit,
  ) async {
    try {
      final row = await _profilesRepository.loadProfileWithSecrets(event.id);
      if (row == null) {
        emit(
          state.copyWith(
            message: _nextMessage(
              AppText.current.couldNotSaveChanges,
              error: true,
            ),
          ),
        );
        return;
      }
      await _profilesRepository.upsertProfile(
        row.profile.copyWith(user: event.user.trim()),
        password: event.password,
        psk: row.psk,
        proxyPassword: row.proxyPassword,
      );
      await _profilesRepository.clearProfileAwaitingCredentials(event.id);
      await _reloadProfiles(emit, preferredActiveId: event.id);
      emit(
        state.copyWith(message: _nextMessage(AppText.current.profileSaved)),
      );
    } catch (_) {
      emit(
        state.copyWith(
          message: _nextMessage(AppText.current.couldNotSaveChanges, error: true),
        ),
      );
    }
  }

  Future<void> _onExportBundleRequested(
    ProfilesExportBundleRequested event,
    Emitter<ProfilesState> emit,
  ) async {
    try {
      await _profilesRepository.exportProfileBundle(
        profileIds: event.profileIds,
        bundleName: event.bundleName,
        password: event.password,
      );
      emit(
        state.copyWith(
          message: _nextMessage(AppText.current.profileSetFileReady),
        ),
      );
    } on ProfileRepositoryException catch (error) {
      emit(state.copyWith(message: _nextMessage(error.message, error: true)));
    } catch (_) {
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.current.couldNotExportProfileSet,
            error: true,
          ),
        ),
      );
    }
  }

  Future<void> _onBundleImportRequested(
    ProfilesBundleImportRequested event,
    Emitter<ProfilesState> emit,
  ) async {
    try {
      final result = await _profilesRepository.importProfileBundle(
        bundle: event.bundle,
        choices: event.choices,
      );
      // A set arriving while a tunnel is up must not move the connection to
      // one of its profiles, the same rule a single import follows.
      final select =
          state.selectImportedProfileWhenIdle && result.firstImportedId != null;
      await _reloadProfiles(
        emit,
        preferredActiveId: select
            ? result.firstImportedId
            : state.activeProfileId,
      );
      if (select) {
        await _profilesRepository.setLastProfileId(result.firstImportedId);
      }
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.current.importedProfileSet(
              result.added,
              result.replaced,
              result.skipped,
            ),
          ),
        ),
      );
    } on FormatException catch (error) {
      emit(state.copyWith(message: _nextMessage(error.message, error: true)));
    } catch (_) {
      emit(
        state.copyWith(
          message: _nextMessage(
            AppText.current.couldNotImportProfileSet,
            error: true,
          ),
        ),
      );
    }
  }

  void _onImportSelectionPolicyChanged(
    ProfilesImportSelectionPolicyChanged event,
    Emitter<ProfilesState> emit,
  ) {
    emit(
      state.copyWith(
        selectImportedProfileWhenIdle: event.selectImportedProfileWhenIdle,
      ),
    );
  }

  void _queueIncomingTransfer(IncomingProfileTransfer transfer) {
    add(
      ProfilesImportRequested(
        ImportTransferRequest(
          transfer: transfer,
          selectAsLastProfile: state.selectImportedProfileWhenIdle,
        ),
      ),
    );
  }

  Future<void> _reloadProfiles(
    Emitter<ProfilesState> emit, {
    String? preferredActiveId,
    String? preferredActiveGroupId,
  }) async {
    emit(state.copyWith(loading: true, clearMessage: true));
    final profiles = await _profilesRepository.loadProfiles();
    // Groups are read on every profile reload rather than on their own: a
    // deleted profile takes its membership rows with it, so a group's contents
    // can change without the group having been touched.
    final groups = await _profilesRepository.loadFailoverGroups();
    final wantedGroupId = preferredActiveGroupId ?? state.activeGroupId;
    final groupId = groups.any((group) => group.id == wantedGroupId)
        ? wantedGroupId
        : null;
    final targetId =
        groupId == null &&
            preferredActiveId != null &&
            profiles.any((profile) => profile.id == preferredActiveId)
        ? preferredActiveId
        : null;
    ProfileSecretRow? targetRow;
    if (targetId != null) {
      targetRow = await _profilesRepository.loadProfileWithSecrets(targetId);
    }
    // Narrowed to profiles that still exist, so an id left behind by a deletion
    // the store did not see cannot mark an unrelated profile later.
    final awaiting = (await _profilesRepository.loadProfilesAwaitingCredentials())
        .where((id) => profiles.any((profile) => profile.id == id))
        .toSet();
    emit(
      state.copyWith(
        loading: false,
        profiles: profiles,
        profilesAwaitingCredentials: awaiting,
        activeProfileId: targetRow == null ? null : targetId,
        activeProfileRow: targetRow,
        groups: groups,
        activeGroupId: groupId,
        clearActiveGroupId: groupId == null,
        // A group taking over is the one case where the reload has to drop a
        // profile it would otherwise have kept; the button starts one thing.
        clearActiveProfileId: groupId != null,
        clearActiveProfileRow: groupId != null,
      ),
    );
  }

  HomeMessage _nextMessage(String text, {bool error = false}) {
    _messageId += 1;
    return HomeMessage(id: _messageId, text: text, error: error);
  }

  @override
  Future<void> close() async {
    await _transferSub?.cancel();
    return super.close();
  }
}
