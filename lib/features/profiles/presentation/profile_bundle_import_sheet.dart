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

  static Future<List<BundleImportChoice>?> show(
    BuildContext context, {
    required ProfileBundle bundle,
    required List<Profile> existing,
  }) {
    final theme = Theme.of(context);
    return showModalBottomSheet<List<BundleImportChoice>>(
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
  }

  void _submit() {
    Navigator.of(context).pop(<BundleImportChoice>[
      for (var i = 0; i < widget.bundle.length; i++)
        BundleImportChoice(
          entryIndex: i,
          action: _chosen[i] ? _actions[i] : BundleImportAction.skip,
          targetProfileId: _matches[i]?.id,
        ),
    ]);
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
            const SizedBox(height: 20),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: Text(t.cancel),
                ),
                const SizedBox(width: 8),
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
