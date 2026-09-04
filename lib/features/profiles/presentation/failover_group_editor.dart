// The editor for a failover group (SPEC 10.1). Shown inside the profile picker
// sheet, beside the profile editor, because a group is made of the profiles
// that sheet already lists.
import 'package:flutter/material.dart';

import 'package:tunnel_forge/features/profiles/domain/failover_group.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';

/// Name, per-member budget, and the ordered membership of one group.
///
/// The widget owns the draft and hands a whole [FailoverGroup] to [onSave]; it
/// never writes to a store itself. That keeps the same view usable for a new
/// group and an existing one, and keeps the reordering — the part with real
/// logic in it — out of any bloc.
class FailoverGroupEditorView extends StatefulWidget {
  const FailoverGroupEditorView({
    super.key,
    required this.group,
    required this.profiles,
    required this.onClose,
    required this.onSave,
    this.saving = false,
  });

  /// The group being edited, or null for a new one.
  final FailoverGroup? group;

  /// Every saved profile, which is what a group can be built out of.
  final List<Profile> profiles;

  final VoidCallback onClose;
  final ValueChanged<FailoverGroup> onSave;

  /// Whether a save is in flight, so the form cannot be submitted twice.
  final bool saving;

  @override
  State<FailoverGroupEditorView> createState() =>
      _FailoverGroupEditorViewState();
}

class _FailoverGroupEditorViewState extends State<FailoverGroupEditorView> {
  late final TextEditingController _nameController;
  late final TextEditingController _timeoutController;

  /// The draft membership, in the order the group would try it.
  late List<String> _memberIds;

  String? _nameError;
  String? _timeoutError;
  String? _membersError;

  @override
  void initState() {
    super.initState();
    final group = widget.group;
    _nameController = TextEditingController(text: group?.name ?? '');
    _timeoutController = TextEditingController(
      text: (group?.connectTimeoutSec ?? FailoverGroup.defaultConnectTimeoutSec)
          .toString(),
    );
    // Members that no longer name a saved profile are dropped as the draft is
    // built: they cannot be shown, moved or removed, and saving them back would
    // only have the host drop them again.
    _memberIds = <String>[
      for (final id in group?.memberIds ?? const <String>[])
        if (widget.profiles.any((profile) => profile.id == id)) id,
    ];
  }

  @override
  void dispose() {
    _nameController.dispose();
    _timeoutController.dispose();
    super.dispose();
  }

  Profile? _profileFor(String id) {
    for (final profile in widget.profiles) {
      if (profile.id == id) return profile;
    }
    return null;
  }

  List<Profile> get _candidates => [
    for (final profile in widget.profiles)
      if (!_memberIds.contains(profile.id)) profile,
  ];

  void _addMember(String id) {
    setState(() {
      if (!_memberIds.contains(id)) _memberIds = [..._memberIds, id];
      _membersError = null;
    });
  }

  void _removeMember(String id) {
    setState(() {
      _memberIds = [
        for (final member in _memberIds)
          if (member != id) member,
      ];
    });
  }

  /// Moves the member at [from] to [to], the way a drag or a menu asks for.
  ///
  /// [ReorderableListView] reports the destination as an index in the list
  /// *before* the moved row is taken out, so an item travelling downwards has
  /// to lose one. Both callers go through here so that only one of them has to
  /// know that.
  void _moveMember(int from, int to) {
    if (from < 0 || from >= _memberIds.length) return;
    final target = to > from ? to - 1 : to;
    if (target < 0 || target >= _memberIds.length || target == from) return;
    setState(() {
      final next = [..._memberIds];
      next.insert(target, next.removeAt(from));
      _memberIds = next;
    });
  }

  void _submit() {
    if (widget.saving) return;
    final t = AppLocalizations.of(context);
    final name = _nameController.text.trim();
    final seconds = int.tryParse(_timeoutController.text.trim());
    final inRange =
        seconds != null &&
        seconds >= FailoverGroup.minConnectTimeoutSec &&
        seconds <= FailoverGroup.maxConnectTimeoutSec;
    setState(() {
      _nameError = name.isEmpty ? t.failoverGroupNeedsName : null;
      _timeoutError = inRange
          ? null
          : t.failoverConnectTimeoutOutOfRange(
              FailoverGroup.minConnectTimeoutSec,
              FailoverGroup.maxConnectTimeoutSec,
            );
      // An empty group is refused here rather than saved and refused at connect
      // time: a group that cannot be started is not a state worth storing.
      _membersError = _memberIds.isEmpty ? t.failoverGroupNeedsAProfile : null;
    });
    if (_nameError != null || _timeoutError != null || _membersError != null) {
      return;
    }
    final existing = widget.group;
    widget.onSave(
      FailoverGroup(
        id: existing?.id ?? '',
        name: name,
        connectTimeoutSec: seconds!,
        createdAt: existing?.createdAt ?? 0,
        memberIds: List<String>.unmodifiable(_memberIds),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final tt = theme.textTheme;
    final t = AppLocalizations.of(context);
    final candidates = _candidates;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(4, 0, 8, 4),
          child: Row(
            children: [
              IconButton(
                key: const Key('failover_group_editor_close'),
                tooltip: t.cancel,
                icon: const Icon(Icons.arrow_back),
                onPressed: widget.saving ? null : widget.onClose,
              ),
              Expanded(
                child: Text(
                  widget.group == null
                      ? t.newFailoverGroup
                      : t.editFailoverGroup,
                  style: tt.titleLarge,
                ),
              ),
              FilledButton(
                key: const Key('failover_group_editor_save'),
                onPressed: widget.saving ? null : _submit,
                child: Text(t.save),
              ),
            ],
          ),
        ),
        if (widget.saving)
          const Padding(
            padding: EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: LinearProgressIndicator(),
          ),
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
            children: [
              TextField(
                key: const Key('failover_group_name'),
                controller: _nameController,
                textInputAction: TextInputAction.next,
                decoration: InputDecoration(
                  labelText: t.failoverGroupNameLabel,
                  hintText: t.failoverGroupNameHint,
                  errorText: _nameError,
                  border: const OutlineInputBorder(),
                ),
                onChanged: (_) {
                  if (_nameError != null) setState(() => _nameError = null);
                },
              ),
              const SizedBox(height: 16),
              TextField(
                key: const Key('failover_group_connect_timeout'),
                controller: _timeoutController,
                keyboardType: TextInputType.number,
                decoration: InputDecoration(
                  labelText: t.failoverConnectTimeoutLabel,
                  suffixText: t.seconds,
                  helperText: t.failoverConnectTimeoutHelp,
                  helperMaxLines: 3,
                  errorText: _timeoutError,
                  errorMaxLines: 2,
                  border: const OutlineInputBorder(),
                ),
                onChanged: (_) {
                  if (_timeoutError != null) {
                    setState(() => _timeoutError = null);
                  }
                },
              ),
              const SizedBox(height: 24),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      t.failoverGroupMembersLabel,
                      style: tt.titleSmall,
                    ),
                  ),
                  TextButton.icon(
                    key: const Key('failover_group_add_member'),
                    onPressed: candidates.isEmpty
                        ? null
                        : () => _showAddMemberMenu(candidates),
                    icon: const Icon(Icons.add, size: 18),
                    label: Text(t.addProfileToFailoverGroup),
                  ),
                ],
              ),
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Text(
                  candidates.isEmpty && widget.profiles.isNotEmpty
                      ? t.everyProfileAlreadyInGroup
                      : t.failoverGroupTriesInOrder,
                  style: tt.bodySmall?.copyWith(color: cs.onSurfaceVariant),
                ),
              ),
              if (_memberIds.isEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  child: Text(
                    _membersError ?? t.failoverGroupIsEmpty,
                    style: tt.bodyMedium?.copyWith(
                      color: _membersError == null
                          ? cs.onSurfaceVariant
                          : cs.error,
                    ),
                  ),
                )
              else
                ReorderableListView.builder(
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  buildDefaultDragHandles: false,
                  itemCount: _memberIds.length,
                  onReorder: _moveMember,
                  itemBuilder: (context, index) =>
                      _buildMemberTile(context, index),
                ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildMemberTile(BuildContext context, int index) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final tt = theme.textTheme;
    final t = AppLocalizations.of(context);
    final id = _memberIds[index];
    final profile = _profileFor(id);

    return ListTile(
      key: ValueKey<String>('failover_member_$id'),
      contentPadding: EdgeInsets.zero,
      leading: ReorderableDragStartListener(
        index: index,
        child: CircleAvatar(
          radius: 14,
          backgroundColor: cs.secondaryContainer,
          child: Text(
            '${index + 1}',
            style: tt.labelMedium?.copyWith(color: cs.onSecondaryContainer),
          ),
        ),
      ),
      title: Text(
        profile?.displayName ?? id,
        style: tt.titleSmall,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: profile == null
          ? null
          : Text(
              '${profile.protocol.label} · ${profile.server}',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: tt.bodySmall?.copyWith(color: cs.onSurfaceVariant),
            ),
      trailing: PopupMenuButton<_MemberAction>(
        key: Key('failover_member_actions_$id'),
        tooltip: t.failoverGroupActions,
        onSelected: (action) {
          switch (action) {
            case _MemberAction.moveUp:
              _moveMember(index, index - 1);
              break;
            case _MemberAction.moveDown:
              // A downward move is expressed the way a drag reports it, two
              // past the current index, so both paths meet in one place.
              _moveMember(index, index + 2);
              break;
            case _MemberAction.remove:
              _removeMember(id);
              break;
          }
        },
        itemBuilder: (context) => [
          PopupMenuItem<_MemberAction>(
            value: _MemberAction.moveUp,
            enabled: index > 0,
            child: Text(t.moveUpInFailoverGroup),
          ),
          PopupMenuItem<_MemberAction>(
            value: _MemberAction.moveDown,
            enabled: index < _memberIds.length - 1,
            child: Text(t.moveDownInFailoverGroup),
          ),
          PopupMenuItem<_MemberAction>(
            value: _MemberAction.remove,
            child: Text(
              t.removeFromFailoverGroup,
              style: TextStyle(color: cs.error),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _showAddMemberMenu(List<Profile> candidates) async {
    final t = AppLocalizations.of(context);
    final picked = await showModalBottomSheet<String>(
      context: context,
      useRootNavigator: true,
      showDragHandle: true,
      builder: (sheetContext) {
        final tt = Theme.of(sheetContext).textTheme;
        final cs = Theme.of(sheetContext).colorScheme;
        return SafeArea(
          child: ListView(
            shrinkWrap: true,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
                child: Text(t.addProfileToFailoverGroup, style: tt.titleMedium),
              ),
              for (final profile in candidates)
                ListTile(
                  key: Key('failover_candidate_${profile.id}'),
                  title: Text(profile.displayName),
                  subtitle: Text(
                    '${profile.protocol.label} · ${profile.server}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: tt.bodySmall?.copyWith(color: cs.onSurfaceVariant),
                  ),
                  onTap: () => Navigator.of(sheetContext).pop(profile.id),
                ),
            ],
          ),
        );
      },
    );
    if (!mounted || picked == null) return;
    _addMember(picked);
  }
}

enum _MemberAction { moveUp, moveDown, remove }
