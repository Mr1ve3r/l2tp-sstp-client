// Opening a sealed file, and deciding what of a set actually lands.
import 'package:flutter/material.dart';

import 'package:tunnel_forge/core/vpn_protocol.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_bundle.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';

/// Asks for the password of a sealed `.tfp`.
///
/// Returns null when the user gave up. [errorText] carries a previous refusal
/// back in, because a wrong password has to land in the same field it was typed
/// in rather than in a message behind a closed dialog.
class SealedFilePasswordDialog extends StatefulWidget {
  const SealedFilePasswordDialog({super.key, this.errorText});

  final String? errorText;

  static Future<String?> show(BuildContext context, {String? errorText}) {
    return showDialog<String>(
      context: context,
      useRootNavigator: true,
      builder: (dialogContext) =>
          SealedFilePasswordDialog(errorText: errorText),
    );
  }

  @override
  State<SealedFilePasswordDialog> createState() =>
      _SealedFilePasswordDialogState();
}

class _SealedFilePasswordDialogState extends State<SealedFilePasswordDialog> {
  final TextEditingController _password = TextEditingController();
  bool _reveal = false;

  @override
  void dispose() {
    _password.dispose();
    super.dispose();
  }

  void _submit() {
    if (_password.text.isEmpty) return;
    Navigator.of(context).pop(_password.text);
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return AlertDialog(
      // Same reason as the credentials prompt: the field autofocuses, so the
      // keyboard has already taken the height this was measured against.
      scrollable: true,
      title: Text(t.encryptedFileTitle),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(t.encryptedFileBody),
          const SizedBox(height: 16),
          TextField(
            key: const Key('sealed_file_password'),
            controller: _password,
            autofocus: true,
            obscureText: !_reveal,
            onSubmitted: (_) => _submit(),
            decoration: InputDecoration(
              labelText: t.password,
              errorText: widget.errorText,
              border: const OutlineInputBorder(),
              suffixIcon: IconButton(
                tooltip: t.showPassword,
                icon: Icon(_reveal ? Icons.visibility_off : Icons.visibility),
                onPressed: () => setState(() => _reveal = !_reveal),
              ),
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(t.cancel),
        ),
        FilledButton(
          key: const Key('sealed_file_password_submit'),
          onPressed: _submit,
          child: Text(t.openFile),
        ),
      ],
    );
  }
}

/// What arrived in a set, and what to do with each of it.
///
/// Everything is ticked and, where a profile like it is already here, set to
/// replace. A set is handed out again every time the organisation changes
/// something, so the ordinary case is "all of it, over the top of the last
/// handout"; the choices are here for the recipient who keeps one of their own.
class ProfileBundleImportSheet extends StatefulWidget {
  const ProfileBundleImportSheet({
    super.key,
    required this.bundle,
    required this.existing,
  });

  final ProfileBundle bundle;

  /// The profiles already on this device, which is what a conflict is against.
  final List<Profile> existing;

  static Future<BundleImportSelection?> show(
    BuildContext context, {
    required ProfileBundle bundle,
    required List<Profile> existing,
  }) {
    final theme = Theme.of(context);
    return showModalBottomSheet<BundleImportSelection>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useRootNavigator: true,
      useSafeArea: true,
      backgroundColor:
          theme.bottomSheetTheme.backgroundColor ??
          theme.colorScheme.surfaceContainerLow,
      builder: (sheetContext) =>
          ProfileBundleImportSheet(bundle: bundle, existing: existing),
    );
  }

  @override
  State<ProfileBundleImportSheet> createState() =>
      _ProfileBundleImportSheetState();
}

class _ProfileBundleImportSheetState extends State<ProfileBundleImportSheet> {
  late final List<Profile?> _matches;
  late final List<bool> _chosen;
  late final List<BundleImportAction> _actions;
  late final List<bool> _chosenGroups;

  @override
  void initState() {
    super.initState();
    _matches = [
      for (final entry in widget.bundle.entries)
        matchingProfileFor(entry, widget.existing),
    ];
    _chosen = List<bool>.filled(widget.bundle.length, true);
    _actions = [
      for (final match in _matches)
        match == null ? BundleImportAction.add : BundleImportAction.replace,
    ];
    _chosenGroups = List<bool>.filled(widget.bundle.groups.length, true);
  }

  /// How many members of [group] are still ticked.
  ///
  /// A group is built from what actually lands, so this is what it would come
  /// out as. At zero there is nothing to build and the row is disabled.
  int _remainingMembers(BundledFailoverGroup group) => group.memberIndexes
      .where((index) => index < _chosen.length && _chosen[index])
      .length;

  void _submit() {
    Navigator.of(context).pop(
      BundleImportSelection(
        choices: <BundleImportChoice>[
          for (var i = 0; i < widget.bundle.length; i++)
            BundleImportChoice(
              entryIndex: i,
              action: _chosen[i] ? _actions[i] : BundleImportAction.skip,
              targetProfileId: _matches[i]?.id,
            ),
        ],
        groupIndexes: <int>[
          for (var i = 0; i < widget.bundle.groups.length; i++)
            if (_chosenGroups[i] &&
                _remainingMembers(widget.bundle.groups[i]) > 0)
              i,
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final t = AppLocalizations.of(context);
    final chosenCount = _chosen.where((on) => on).length;

    return SafeArea(
      top: false,
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              widget.bundle.name.isEmpty
                  ? t.importProfileSet
                  : widget.bundle.name,
              style: theme.textTheme.titleLarge,
            ),
            const SizedBox(height: 4),
            Text(
              t.profileSetCount(widget.bundle.length),
              style: theme.textTheme.bodySmall?.copyWith(
                color: cs.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 12),
            for (var i = 0; i < widget.bundle.length; i++)
              _entryTile(context, i),
            if (widget.bundle.groups.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(t.failoverGroups, style: theme.textTheme.titleSmall),
              for (var i = 0; i < widget.bundle.groups.length; i++)
                _groupTile(context, i),
            ],
            const SizedBox(height: 20),
            OverflowBar(
              alignment: MainAxisAlignment.end,
              overflowAlignment: OverflowBarAlignment.end,
              spacing: 8,
              overflowSpacing: 8,
              children: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: Text(t.cancel),
                ),
                FilledButton(
                  key: const Key('profile_set_import_submit'),
                  onPressed: chosenCount == 0 ? null : _submit,
                  child: Text(t.importSelected),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  /// One of the set's failover groups.
  ///
  /// The subtitle counts what would actually land rather than what the set
  /// says, because unticking a profile above silently shortens the group, and
  /// a recipient who has just skipped two of three members should be able to
  /// see that before they import a group of one.
  Widget _groupTile(BuildContext context, int index) {
    final t = AppLocalizations.of(context);
    final group = widget.bundle.groups[index];
    final remaining = _remainingMembers(group);

    return CheckboxListTile(
      key: Key('profile_set_group_$index'),
      value: _chosenGroups[index] && remaining > 0,
      onChanged: remaining == 0
          ? null
          : (on) => setState(() => _chosenGroups[index] = on ?? false),
      dense: true,
      contentPadding: EdgeInsets.zero,
      controlAffinity: ListTileControlAffinity.leading,
      title: Text(group.name),
      subtitle: Text(
        remaining == 0
            ? t.groupHasNoChosenProfiles
            : t.failoverGroupProfileCount(remaining),
      ),
    );
  }

  Widget _entryTile(BuildContext context, int index) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final t = AppLocalizations.of(context);
    final entry = widget.bundle.entries[index];
    final profile = entry.profile;
    final match = _matches[index];

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        CheckboxListTile(
          key: Key('profile_set_entry_$index'),
          value: _chosen[index],
          onChanged: (on) => setState(() => _chosen[index] = on ?? false),
          dense: true,
          contentPadding: EdgeInsets.zero,
          controlAffinity: ListTileControlAffinity.leading,
          title: Text(
            profile.displayName.trim().isEmpty
                ? profile.server
                : profile.displayName,
          ),
          subtitle: Text(
            '${profile.protocol == VpnProtocol.sstp ? 'SSTP' : 'L2TP'}'
            ' · ${profile.server}'
            ' · ${t.profileSetEntryCertificates(entry.certificates.length)}',
          ),
        ),
        if (match != null && _chosen[index])
          Padding(
            padding: const EdgeInsets.only(left: 32, bottom: 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  t.alreadyOnThisDevice,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: cs.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 6),
                SegmentedButton<BundleImportAction>(
                  key: Key('profile_set_conflict_$index'),
                  showSelectedIcon: false,
                  style: const ButtonStyle(
                    visualDensity: VisualDensity.compact,
                  ),
                  segments: [
                    ButtonSegment(
                      value: BundleImportAction.replace,
                      label: Text(t.conflictReplace),
                    ),
                    ButtonSegment(
                      value: BundleImportAction.add,
                      label: Text(t.conflictAdd),
                    ),
                    ButtonSegment(
                      value: BundleImportAction.skip,
                      label: Text(t.conflictSkip),
                    ),
                  ],
                  selected: {_actions[index]},
                  onSelectionChanged: (selection) =>
                      setState(() => _actions[index] = selection.first),
                ),
              ],
            ),
          ),
      ],
    );
  }
}
