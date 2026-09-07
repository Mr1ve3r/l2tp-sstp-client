// Asking for the login and password a shared set deliberately left out.
import 'package:flutter/material.dart';

import 'package:tunnel_forge/l10n/app_localizations.dart';

/// What the dialog collected.
class ProfileCredentials {
  const ProfileCredentials({required this.user, required this.password});

  final String user;
  final String password;
}

/// The prompt shown when connecting a profile that has no credentials yet.
///
/// It stands in front of the connection rather than beside it. A set is handed
/// out without a login on purpose, so the first connect is guaranteed to be the
/// one that has nothing to authenticate with; letting it go out and fail would
/// spend twenty seconds and a server-side failed login to say what this says
/// straight away.
class ProfileCredentialsDialog extends StatefulWidget {
  const ProfileCredentialsDialog({
    super.key,
    required this.profileName,
    this.initialUser = '',
  });

  final String profileName;
  final String initialUser;

  static Future<ProfileCredentials?> show(
    BuildContext context, {
    required String profileName,
    String initialUser = '',
  }) {
    return showDialog<ProfileCredentials>(
      context: context,
      useRootNavigator: true,
      builder: (dialogContext) => ProfileCredentialsDialog(
        profileName: profileName,
        initialUser: initialUser,
      ),
    );
  }

  @override
  State<ProfileCredentialsDialog> createState() =>
      _ProfileCredentialsDialogState();
}

class _ProfileCredentialsDialogState extends State<ProfileCredentialsDialog> {
  late final TextEditingController _user = TextEditingController(
    text: widget.initialUser,
  );
  final TextEditingController _password = TextEditingController();
  bool _reveal = false;

  @override
  void dispose() {
    _user.dispose();
    _password.dispose();
    super.dispose();
  }

  void _submit() {
    if (_user.text.trim().isEmpty || _password.text.isEmpty) return;
    Navigator.of(context).pop(
      ProfileCredentials(user: _user.text.trim(), password: _password.text),
    );
  }

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);
    return AlertDialog(
      // The keyboard is up the moment this opens — the username field takes
      // focus itself — so the dialog is laid out against roughly half a screen
      // and two fields plus an explanation do not fit in it.
      scrollable: true,
      title: Text(t.enterCredentialsTitle),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(t.enterCredentialsBody),
          const SizedBox(height: 16),
          TextField(
            key: const Key('profile_credentials_user'),
            controller: _user,
            autofocus: true,
            textInputAction: TextInputAction.next,
            decoration: InputDecoration(
              labelText: t.username,
              border: const OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            key: const Key('profile_credentials_password'),
            controller: _password,
            obscureText: !_reveal,
            textInputAction: TextInputAction.done,
            onSubmitted: (_) => _submit(),
            decoration: InputDecoration(
              labelText: t.password,
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
          key: const Key('profile_credentials_submit'),
          onPressed: _submit,
          child: Text(t.connectAction),
        ),
      ],
    );
  }
}
