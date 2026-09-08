// Choosing what goes into a shared set, and the password that seals it.
import 'package:flutter/material.dart';

import 'package:tunnel_forge/core/vpn_protocol.dart';
import 'package:tunnel_forge/features/profiles/domain/failover_group.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';

/// What the sheet collected: which profiles, under what name, sealed with what.
class ProfileBundleExportRequest {
  const ProfileBundleExportRequest({
    required this.profileIds,
    required this.bundleName,
    required this.password,
    this.groupIds = const <String>[],
  });

  final List<String> profileIds;
  final String bundleName;
  final String password;

  /// The failover groups to carry along. Only groups all of whose members are
  /// in [profileIds] can be ticked, so the set never describes a group that
  /// arrives missing a member.
  final List<String> groupIds;
}

/// What exporting one profile decided: seal it under a password, or not.
class SingleExportChoice {
  const SingleExportChoice(this.password);

  /// Null when the file is to carry settings only, as it always used to.
  final String? password;
}

/// Offers to put a password on a single profile's export.
///
/// Without one the file is what it has always been — settings, no secrets —
/// which is right for handing a colleague a server to try. With one it carries
/// the login, the pre-shared key and the proxy password, which is what moving
/// your own profile to a new phone actually needs. The confirmation field is
/// here for the same reason it is on a set: a typo in a password that seals a
/// file is only discovered when the file will not open.
class SingleExportPasswordDialog extends StatefulWidget {
  const SingleExportPasswordDialog({super.key});

  static Future<SingleExportChoice?> show(BuildContext context) {
    return showDialog<SingleExportChoice>(
      context: context,
      useRootNavigator: true,
      builder: (dialogContext) => const SingleExportPasswordDialog(),
    );
  }

  @override
  State<SingleExportPasswordDialog> createState() =>
      _SingleExportPasswordDialogState();
}

class _SingleExportPasswordDialogState
    extends State<SingleExportPasswordDialog> {
  final TextEditingController _password = TextEditingController();
  final TextEditingController _confirm = TextEditingController();
  bool _reveal = false;
  String? _error;

  @override
  void dispose() {
    _password.dispose();
    _confirm.dispose();
    super.dispose();
  }

  void _submit() {
    final t = AppLocalizations.of(context);
    final password = _password.text;
    setState(() {
      _error = switch (password) {
        _ when password.length < ProfileBundleExportSheet.minPasswordLength =>
          t.passwordTooShort(ProfileBundleExportSheet.minPasswordLength),
        _ when password != _confirm.text => t.passwordsDoNotMatch,
        _ => null,
      };
    });
    if (_error != null) return;
    Navigator.of(context).pop(SingleExportChoice(password));
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return AlertDialog(
      scrollable: true,
      title: Text(t.exportTfp),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(t.exportPasswordExplanation),
          const SizedBox(height: 16),
          TextField(
            key: const Key('single_export_password'),
            controller: _password,
            autofocus: true,
            obscureText: !_reveal,
            textInputAction: TextInputAction.next,
            decoration: InputDecoration(
              labelText: t.setPassword,
              errorText: _error,
              border: const OutlineInputBorder(),
              suffixIcon: IconButton(
                tooltip: t.showPassword,
                icon: Icon(_reveal ? Icons.visibility_off : Icons.visibility),
                onPressed: () => setState(() => _reveal = !_reveal),
              ),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            key: const Key('single_export_password_confirm'),
            controller: _confirm,
            obscureText: !_reveal,
            textInputAction: TextInputAction.done,
            onSubmitted: (_) => _submit(),
            decoration: InputDecoration(
              labelText: t.confirmSetPassword,
              border: const OutlineInputBorder(),
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(t.cancel),
        ),
        TextButton(
          key: const Key('single_export_without_secrets'),
          onPressed: () =>
              Navigator.of(context).pop(const SingleExportChoice(null)),
          child: Text(t.exportWithoutSecrets),
        ),
        FilledButton(
          key: const Key('single_export_submit'),
          onPressed: _submit,
          child: Text(t.exportSet),
        ),
      ],
    );
  }
}

/// The form behind "Export a set…".
///
/// The password is asked for twice and never optional. A set is made to be sent
/// somewhere — a chat, a drive, a mail server — and it carries the pre-shared
/// key, so the one thing standing between the file and whoever else touches it
/// is the password. A typo in a password that seals a file is discovered by the
/// recipient, at which point nothing can be done about it, which is why the
/// second field is here rather than left as tidiness.
class ProfileBundleExportSheet extends StatefulWidget {
  const ProfileBundleExportSheet({
    super.key,
    required this.profiles,
    this.groups = const <FailoverGroup>[],
  });

  final List<Profile> profiles;

  /// The failover groups this device has, offered when their members are all
  /// in the set.
  final List<FailoverGroup> groups;

  /// The shortest password the form accepts. PBKDF2 buys time against a
  /// guesser, not against a password there is nothing to guess.
  static const int minPasswordLength = 8;

  static Future<ProfileBundleExportRequest?> show(
    BuildContext context, {
    required List<Profile> profiles,
    List<FailoverGroup> groups = const <FailoverGroup>[],
  }) {
    final theme = Theme.of(context);
    return showModalBottomSheet<ProfileBundleExportRequest>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useRootNavigator: true,
      useSafeArea: true,
      backgroundColor:
          theme.bottomSheetTheme.backgroundColor ??
          theme.colorScheme.surfaceContainerLow,
      builder: (sheetContext) =>
          ProfileBundleExportSheet(profiles: profiles, groups: groups),
    );
  }

  @override
  State<ProfileBundleExportSheet> createState() =>
      _ProfileBundleExportSheetState();
}

class _ProfileBundleExportSheetState extends State<ProfileBundleExportSheet> {
  final TextEditingController _name = TextEditingController();
  final TextEditingController _password = TextEditingController();
  final TextEditingController _confirm = TextEditingController();
  final Set<String> _selected = <String>{};
  final Set<String> _selectedGroups = <String>{};
  bool _reveal = false;
  String? _selectionError;
  String? _passwordError;

  @override
  void initState() {
    super.initState();
    // Every profile starts chosen: a set is usually all of them, and taking
    // one out is a smaller act than putting six in.
    _selected.addAll(widget.profiles.map((profile) => profile.id));
    _selectedGroups.addAll(
      widget.groups.where(_isComplete).map((group) => group.id),
    );
  }

  /// Whether every member of [group] is currently ticked.
  ///
  /// A group is offered whole or not at all: half a failover group is a list
  /// of servers to try that is missing the ones it would fall back to.
  bool _isComplete(FailoverGroup group) =>
      !group.isEmpty && group.memberIds.every(_selected.contains);

  @override
  void dispose() {
    _name.dispose();
    _password.dispose();
    _confirm.dispose();
    super.dispose();
  }

  void _toggle(String id, bool? on) {
    setState(() {
      if (on ?? false) {
        _selected.add(id);
      } else {
        _selected.remove(id);
      }
      _selectionError = null;
      // A group whose member was just unticked cannot travel, so it unticks
      // itself rather than being silently dropped at export time.
      _selectedGroups.removeWhere(
        (groupId) => !widget.groups
            .where((group) => group.id == groupId)
            .every(_isComplete),
      );
    });
  }

  void _toggleGroup(String id, bool? on) {
    setState(() {
      if (on ?? false) {
        _selectedGroups.add(id);
      } else {
        _selectedGroups.remove(id);
      }
    });
  }

  void _submit() {
    final t = AppLocalizations.of(context);
    final password = _password.text;
    setState(() {
      _selectionError = _selected.isEmpty ? t.noProfilesSelected : null;
      _passwordError = switch (password) {
        _ when password.length < ProfileBundleExportSheet.minPasswordLength =>
          t.passwordTooShort(ProfileBundleExportSheet.minPasswordLength),
        _ when password != _confirm.text => t.passwordsDoNotMatch,
        _ => null,
      };
    });
    if (_selectionError != null || _passwordError != null) return;
    Navigator.of(context).pop(
      ProfileBundleExportRequest(
        // The list order is the sheet's order, not the order boxes were ticked.
        profileIds: [
          for (final profile in widget.profiles)
            if (_selected.contains(profile.id)) profile.id,
        ],
        bundleName: _name.text.trim(),
        password: password,
        groupIds: [
          for (final group in widget.groups)
            if (_selectedGroups.contains(group.id) && _isComplete(group))
              group.id,
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final t = AppLocalizations.of(context);
    final insets = MediaQuery.of(context).viewInsets.bottom;

    return Padding(
      padding: EdgeInsets.only(bottom: insets),
      child: SafeArea(
        top: false,
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(t.exportProfileSet, style: theme.textTheme.titleLarge),
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: cs.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(Icons.info_outline, size: 20, color: cs.primary),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        t.profileSetIncludes,
                        style: theme.textTheme.bodySmall,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              TextField(
                key: const Key('profile_set_name'),
                controller: _name,
                textInputAction: TextInputAction.next,
                decoration: InputDecoration(
                  labelText: t.profileSetName,
                  hintText: t.profileSetNameHint,
                  border: const OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 16),
              Text(t.chooseProfilesForSet, style: theme.textTheme.titleSmall),
              if (_selectionError != null)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(
                    _selectionError!,
                    style: theme.textTheme.bodySmall?.copyWith(color: cs.error),
                  ),
                ),
              for (final profile in widget.profiles)
                CheckboxListTile(
                  key: Key('profile_set_pick_${profile.id}'),
                  value: _selected.contains(profile.id),
                  onChanged: (on) => _toggle(profile.id, on),
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
                    ' · ${profile.server}',
                  ),
                ),
              if (widget.groups.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text(t.chooseGroupsForSet, style: theme.textTheme.titleSmall),
                for (final group in widget.groups)
                  CheckboxListTile(
                    key: Key('profile_set_group_pick_${group.id}'),
                    value: _selectedGroups.contains(group.id),
                    onChanged: _isComplete(group)
                        ? (on) => _toggleGroup(group.id, on)
                        : null,
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    controlAffinity: ListTileControlAffinity.leading,
                    title: Text(group.displayName),
                    subtitle: Text(
                      _isComplete(group)
                          ? t.failoverGroupProfileCount(group.memberIds.length)
                          : t.groupNeedsItsProfiles,
                    ),
                  ),
              ],
              const SizedBox(height: 8),
              TextField(
                key: const Key('profile_set_password'),
                controller: _password,
                obscureText: !_reveal,
                textInputAction: TextInputAction.next,
                decoration: InputDecoration(
                  labelText: t.setPassword,
                  errorText: _passwordError,
                  border: const OutlineInputBorder(),
                  suffixIcon: IconButton(
                    tooltip: t.showPassword,
                    icon: Icon(
                      _reveal ? Icons.visibility_off : Icons.visibility,
                    ),
                    onPressed: () => setState(() => _reveal = !_reveal),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                key: const Key('profile_set_password_confirm'),
                controller: _confirm,
                obscureText: !_reveal,
                textInputAction: TextInputAction.done,
                onSubmitted: (_) => _submit(),
                decoration: InputDecoration(
                  labelText: t.confirmSetPassword,
                  border: const OutlineInputBorder(),
                ),
              ),
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
                    key: const Key('profile_set_export_submit'),
                    onPressed: _submit,
                    child: Text(t.exportSet),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
