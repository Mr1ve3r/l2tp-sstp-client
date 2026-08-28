import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import 'package:tunnel_forge/features/trust/domain/trust_models.dart';
import 'package:tunnel_forge/features/trust/presentation/bloc/certificates_bloc.dart';
import 'package:tunnel_forge/features/trust/presentation/widgets/certificate_detail_sheet.dart';
import 'package:tunnel_forge/features/trust/presentation/widgets/certificate_import_sheet.dart';
import 'package:tunnel_forge/features/trust/presentation/widgets/certificate_texts.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';

/// The server certificate store (SPEC 5.9).
///
/// A list of what the device trusts for SSTP, with the three ways to add to it
/// and enough of each certificate on screen to check a fingerprint against the
/// server that presented it.
class CertificatesPage extends StatelessWidget {
  const CertificatesPage({super.key, this.now});

  /// Overridable so tests do not depend on the wall clock.
  final DateTime? now;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);

    return BlocConsumer<CertificatesBloc, CertificatesState>(
      listenWhen: (previous, current) =>
          previous.failure != current.failure ||
          previous.candidates != current.candidates ||
          previous.exportedPem != current.exportedPem ||
          previous.importedCount != current.importedCount,
      listener: (context, state) => _react(context, l10n, state),
      builder: (context, state) {
        return Scaffold(
          appBar: AppBar(
            title: Text(l10n.serverCertificates),
            bottom: state.busy
                ? const PreferredSize(
                    preferredSize: Size.fromHeight(2),
                    child: LinearProgressIndicator(minHeight: 2),
                  )
                : null,
          ),
          floatingActionButton: FloatingActionButton.extended(
            onPressed: () => _showAddOptions(context, l10n),
            icon: const Icon(Icons.add),
            label: Text(l10n.addCertificate),
          ),
          body: _body(context, l10n, state),
        );
      },
    );
  }

  Widget _body(
    BuildContext context,
    AppLocalizations l10n,
    CertificatesState state,
  ) {
    if (state.loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (state.certificates.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Text(
            l10n.noCertificatesStored,
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodyMedium,
          ),
        ),
      );
    }
    final at = now ?? DateTime.now();
    return ListView.separated(
      padding: const EdgeInsets.only(bottom: 96),
      itemCount: state.certificates.length,
      separatorBuilder: (_, _) => const Divider(height: 1),
      itemBuilder: (context, index) =>
          _tile(context, l10n, state.certificates[index], at),
    );
  }

  Widget _tile(
    BuildContext context,
    AppLocalizations l10n,
    ServerCertificate certificate,
    DateTime at,
  ) {
    final theme = Theme.of(context);
    final expiry = certificate.fields.expiryAt(at);
    return ListTile(
      leading: Icon(
        certificate.fields.isCa ? Icons.account_balance : Icons.verified_user,
        color: switch (expiry) {
          CertificateExpiry.expired ||
          CertificateExpiry.notYetValid => theme.colorScheme.error,
          CertificateExpiry.expiringSoon => theme.colorScheme.tertiary,
          CertificateExpiry.valid => theme.colorScheme.primary,
        },
      ),
      title: Text(
        certificate.alias.isEmpty
            ? certificate.fields.displayName
            : certificate.alias,
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(CertificateTexts.expiry(l10n, certificate.fields, at)),
          Text(
            certificate.usageCount == 0
                ? l10n.certificateUnused
                : l10n.certificateUsedByProfiles(certificate.usageCount),
            style: theme.textTheme.bodySmall,
          ),
        ],
      ),
      isThreeLine: true,
      onTap: () => _showDetail(context, certificate),
    );
  }

  /// Reacts to what the bloc reports: a failure, an import to review, a PEM to
  /// hand over, or a count of what was stored.
  void _react(
    BuildContext context,
    AppLocalizations l10n,
    CertificatesState state,
  ) {
    final bloc = context.read<CertificatesBloc>();
    final failure = state.failure;
    if (failure != null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(CertificateTexts.failure(l10n, failure))),
      );
      bloc.add(const CertificatesNoticeCleared());
      return;
    }
    final imported = state.importedCount;
    if (imported != null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(l10n.certificatesImported(imported))),
      );
      bloc.add(const CertificatesNoticeCleared());
      return;
    }
    final pem = state.exportedPem;
    if (pem != null) {
      bloc.add(const CertificatesNoticeCleared());
      _showPem(context, l10n, pem);
      return;
    }
    if (state.candidates.isNotEmpty) {
      _showImportReview(context, bloc, state.candidates);
    }
  }

  Future<void> _showAddOptions(
    BuildContext context,
    AppLocalizations l10n,
  ) async {
    final bloc = context.read<CertificatesBloc>();
    await showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      builder: (sheetContext) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.folder_open),
              title: Text(l10n.importCertificateFromFile),
              subtitle: Text(l10n.importCertificateFromFileHelp),
              onTap: () {
                Navigator.of(sheetContext).pop();
                bloc.add(const CertificatesFilePickRequested());
              },
            ),
            ListTile(
              leading: const Icon(Icons.content_paste),
              title: Text(l10n.importFromText),
              subtitle: Text(l10n.importFromTextHelp),
              onTap: () {
                Navigator.of(sheetContext).pop();
                _askForPemText(context, bloc);
              },
            ),
            ListTile(
              leading: const Icon(Icons.cloud_download_outlined),
              title: Text(l10n.importFromServer),
              subtitle: Text(l10n.importFromServerHelp),
              onTap: () {
                Navigator.of(sheetContext).pop();
                _askForServer(context, bloc);
              },
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _askForPemText(
    BuildContext context,
    CertificatesBloc bloc,
  ) async {
    final text = await showDialog<String>(
      context: context,
      builder: (_) => const _PemTextDialog(),
    );
    if (text == null || text.isEmpty) return;
    bloc.add(CertificatesPemTextSubmitted(text));
  }

  Future<void> _askForServer(
    BuildContext context,
    CertificatesBloc bloc,
  ) async {
    final target = await showDialog<({String host, int port})>(
      context: context,
      builder: (_) => const _ServerAddressDialog(),
    );
    if (target == null) return;
    bloc.add(
      CertificatesServerChainRequested(host: target.host, port: target.port),
    );
  }

  Future<void> _showImportReview(
    BuildContext context,
    CertificatesBloc bloc,
    List<CertificateCandidate> candidates,
  ) async {
    // A chain came from a server, and only that path needs the warning about
    // comparing fingerprints out of band.
    final fromServer = candidates.any((c) => c.chainPosition != null);
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (sheetContext) => CertificateImportSheet(
        candidates: candidates,
        showDownloadWarning: fromServer,
        onCancel: () {
          Navigator.of(sheetContext).pop();
          bloc.add(const CertificatesImportDismissed());
        },
        onConfirm: (requests) {
          Navigator.of(sheetContext).pop();
          bloc.add(CertificatesImportConfirmed(requests));
        },
      ),
    );
  }

  Future<void> _showDetail(
    BuildContext context,
    ServerCertificate certificate,
  ) async {
    final bloc = context.read<CertificatesBloc>();
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (sheetContext) => CertificateDetailSheet(
        certificate: certificate,
        now: now,
        onExport: () {
          Navigator.of(sheetContext).pop();
          bloc.add(CertificatesExportRequested(certificate.id));
        },
        onDelete: () {
          Navigator.of(sheetContext).pop();
          bloc.add(CertificatesDeleteRequested(certificate.id));
        },
      ),
    );
  }

  Future<void> _showPem(
    BuildContext context,
    AppLocalizations l10n,
    String pem,
  ) async {
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(l10n.certificateExportPem),
        content: SingleChildScrollView(
          child: SelectableText(
            pem,
            style: Theme.of(
              dialogContext,
            ).textTheme.bodySmall?.copyWith(fontFamily: 'monospace'),
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: Text(l10n.close),
          ),
        ],
      ),
    );
  }
}

/// Asks for PEM text (SPEC 5.3 B).
///
/// A widget of its own so the controller outlives the pop: a dialog route
/// animates out over several frames with its field still mounted, and
/// disposing the controller as soon as `showDialog` returns leaves the field
/// using a dead one.
class _PemTextDialog extends StatefulWidget {
  const _PemTextDialog();

  @override
  State<_PemTextDialog> createState() => _PemTextDialogState();
}

class _PemTextDialogState extends State<_PemTextDialog> {
  final TextEditingController _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return AlertDialog(
      title: Text(l10n.importFromText),
      content: TextField(
        controller: _controller,
        maxLines: 8,
        minLines: 4,
        autofocus: true,
        decoration: InputDecoration(hintText: l10n.importFromTextHelp),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.cancel),
        ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(_controller.text.trim()),
          child: Text(l10n.continueLabel),
        ),
      ],
    );
  }
}

/// Asks which server to read a certificate chain from (SPEC 5.3 C).
///
/// Owns its controllers for the same reason as [_PemTextDialog].
class _ServerAddressDialog extends StatefulWidget {
  const _ServerAddressDialog();

  @override
  State<_ServerAddressDialog> createState() => _ServerAddressDialogState();
}

class _ServerAddressDialogState extends State<_ServerAddressDialog> {
  final TextEditingController _host = TextEditingController();
  final TextEditingController _port = TextEditingController(text: '443');

  @override
  void dispose() {
    _host.dispose();
    _port.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return AlertDialog(
      title: Text(l10n.importFromServer),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: _host,
            autofocus: true,
            decoration: InputDecoration(labelText: l10n.certificateHost),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _port,
            keyboardType: TextInputType.number,
            decoration: InputDecoration(labelText: l10n.certificatePort),
          ),
          const SizedBox(height: 12),
          Text(
            l10n.importFromServerWarning,
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.cancel),
        ),
        TextButton(onPressed: _submit, child: Text(l10n.continueLabel)),
      ],
    );
  }

  void _submit() {
    final host = _host.text.trim();
    final port = int.tryParse(_port.text.trim());
    if (host.isEmpty || port == null || port < 1 || port > 65535) return;
    Navigator.of(context).pop((host: host, port: port));
  }
}
