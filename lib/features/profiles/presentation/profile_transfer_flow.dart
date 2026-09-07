// Landing a .tfp, whatever shape and wherever it came from.
//
// This lives outside any one screen because a file arrives by two routes that
// do not share a widget: the import menu inside the profile sheet, and a tap on
// an attachment, which reaches the home page with no sheet open at all. Both
// need the same password prompt and the same set preview, and a second copy of
// either would be the copy that fell behind.
import 'dart:async';

import 'package:flutter/material.dart';

import 'package:tunnel_forge/app/ui/app_scaffold_messenger.dart';
import 'package:tunnel_forge/features/home/domain/home_models.dart';
import 'package:tunnel_forge/features/home/presentation/bloc/profiles_bloc.dart';
import 'package:tunnel_forge/features/profiles/data/profile_store.dart';
import 'package:tunnel_forge/features/profiles/data/profile_transfer_contract.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_bundle.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_transfer.dart';
import 'package:tunnel_forge/features/profiles/presentation/profile_bundle_import_sheet.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';

/// How long to wait for the bloc to finish an import before saying so.
const Duration _importTimeout = Duration(seconds: 20);

/// Reads [text] and stores what is in it, asking whatever it has to ask.
///
/// Returns once the import has finished or the user has backed out of it.
Future<void> importProfileText(
  BuildContext context, {
  required ProfilesBloc bloc,
  required ProfileStore store,
  required String text,
  required String source,
}) async {
  final document = ProfileTransferEnvelope.looksSealed(text)
      ? await openSealedTransferText(context, store: store, payload: text)
      : ProfileTransferDocument.parse(text);
  if (document == null || !context.mounted) return;
  switch (document) {
    case SingleProfileDocument(:final envelope):
      await _importSingle(
        context,
        bloc: bloc,
        envelope: envelope,
        source: source,
      );
    case ProfileSetDocument(:final bundle):
      await importProfileBundleInteractively(
        context,
        bloc: bloc,
        bundle: bundle,
      );
  }
}

/// Asks for a password until it opens [payload] or the user gives up.
Future<ProfileTransferDocument?> openSealedTransferText(
  BuildContext context, {
  required ProfileStore store,
  required String payload,
}) async {
  String? error;
  while (context.mounted) {
    final password = await SealedFilePasswordDialog.show(
      context,
      errorText: error,
    );
    if (password == null || !context.mounted) return null;
    try {
      return ProfileTransferDocument.parse(
        await store.openExport(payload, password),
      );
    } catch (_) {
      // The host reports a wrong password and a file that is not a container
      // identically, on purpose, so there is one sentence for both.
      error = AppText.current.wrongFilePassword;
    }
  }
  return null;
}

/// Shows what is in [bundle], then stores what was ticked.
Future<void> importProfileBundleInteractively(
  BuildContext context, {
  required ProfilesBloc bloc,
  required ProfileBundle bundle,
}) async {
  final choices = await ProfileBundleImportSheet.show(
    context,
    bundle: bundle,
    existing: bloc.state.profiles,
  );
  if (choices == null || !context.mounted) return;
  await _awaitImport(
    context,
    bloc: bloc,
    dispatch: () => bloc.add(
      ProfilesBundleImportRequested(bundle: bundle, choices: choices),
    ),
  );
}

Future<void> _importSingle(
  BuildContext context, {
  required ProfilesBloc bloc,
  required ProfileTransferEnvelope envelope,
  required String source,
}) async {
  await _awaitImport(
    context,
    bloc: bloc,
    dispatch: () => bloc.add(
      ProfilesImportRequested(
        ImportTransferRequest(
          transfer: IncomingProfileTransfer(
            type: ProfileTransferContract.typeTfpJson,
            // Written out again with its secrets so that a profile taken out
            // of a container arrives exactly as a plain one does.
            data: envelope.toFileJson(secrets: TransferSecrets.all),
            source: source,
          ),
          selectAsLastProfile: bloc.state.selectImportedProfileWhenIdle,
        ),
      ),
    ),
  );
}

/// Runs [dispatch] and waits for the bloc to report on it.
///
/// The listener is set up before the event is added: an import that finishes
/// synchronously would otherwise be over before anything was watching.
Future<void> _awaitImport(
  BuildContext context, {
  required ProfilesBloc bloc,
  required VoidCallback dispatch,
}) async {
  final initialMessageId = bloc.state.message?.id ?? 0;
  final done = bloc.stream
      .firstWhere(
        (state) =>
            !state.loading &&
            state.message != null &&
            state.message!.id > initialMessageId,
      )
      .timeout(_importTimeout);
  dispatch();
  try {
    await done;
  } on TimeoutException {
    if (!context.mounted) return;
    showAppSnackBar(
      context,
      AppText.current.profileImportTimedOut,
      error: true,
    );
  }
}
