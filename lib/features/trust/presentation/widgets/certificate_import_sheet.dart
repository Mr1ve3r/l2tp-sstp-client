import 'package:flutter/material.dart';

import 'package:tunnel_forge/features/trust/domain/trust_models.dart';
import 'package:tunnel_forge/features/trust/presentation/widgets/certificate_texts.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';

/// The second half of every import: what was found, and what to keep.
///
/// Nothing reaches the store until this sheet is confirmed. A file may hold a
/// bundle and a server presents a whole chain, so choosing is the normal case,
/// not an edge one — and the fingerprint sits next to each entry because for a
/// downloaded chain it is the only thing standing between the user and an
/// attacker's certificate (SPEC 5.3).
class CertificateImportSheet extends StatefulWidget {
  const CertificateImportSheet({
    super.key,
    required this.candidates,
    required this.onConfirm,
    required this.onCancel,
    this.showDownloadWarning = false,
  });

  final List<CertificateCandidate> candidates;
  final ValueChanged<List<CertificateImportRequest>> onConfirm;
  final VoidCallback onCancel;

  /// Whether these came off a server, which needs the fingerprint warning.
  final bool showDownloadWarning;

  @override
  State<CertificateImportSheet> createState() => _CertificateImportSheetState();
}

class _CertificateImportSheetState extends State<CertificateImportSheet> {
  late final Set<String> _selected;
  late final Map<String, TextEditingController> _aliases;

  @override
  void initState() {
    super.initState();
    // A single certificate is what the user asked for, so it starts selected.
    // In a bundle or a chain, which one they want is exactly the question.
    _selected = widget.candidates.length == 1
        ? {widget.candidates.single.id}
        : <String>{};
    _aliases = {
      for (final candidate in widget.candidates)
        candidate.id: TextEditingController(text: candidate.fields.displayName),
    };
  }

  @override
  void dispose() {
    for (final controller in _aliases.values) {
      controller.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l10n.addCertificate, style: theme.textTheme.titleLarge),
            if (widget.showDownloadWarning) ...[
              const SizedBox(height: 8),
              _Banner(
                text: l10n.importFromServerWarning,
                color: theme.colorScheme.errorContainer,
                textColor: theme.colorScheme.onErrorContainer,
              ),
            ],
            const SizedBox(height: 12),
            Flexible(
              child: ListView.separated(
                shrinkWrap: true,
                itemCount: widget.candidates.length,
                separatorBuilder: (_, _) => const Divider(height: 20),
                itemBuilder: (context, index) =>
                    _candidateTile(context, l10n, widget.candidates[index]),
              ),
            ),
            const SizedBox(height: 12),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                TextButton(
                  onPressed: widget.onCancel,
                  child: Text(l10n.cancel),
                ),
                const SizedBox(width: 8),
                FilledButton(
                  onPressed: _selected.isEmpty ? null : _confirm,
                  child: Text(l10n.keepSelectedCertificates),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _candidateTile(
    BuildContext context,
    AppLocalizations l10n,
    CertificateCandidate candidate,
  ) {
    final theme = Theme.of(context);
    final selected = _selected.contains(candidate.id);
    final fields = candidate.fields;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        CheckboxListTile(
          value: selected,
          contentPadding: EdgeInsets.zero,
          controlAffinity: ListTileControlAffinity.leading,
          onChanged: (value) => setState(() {
            if (value ?? false) {
              _selected.add(candidate.id);
            } else {
              _selected.remove(candidate.id);
            }
          }),
          title: Text(fields.displayName),
          subtitle: Text(
            candidate.chainPosition == null
                ? fields.issuerDn
                : candidate.chainPosition == 0
                ? l10n.certificateChainLeaf
                : l10n.certificateChainIssuer(candidate.chainPosition!),
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
        ),
        Padding(
          padding: const EdgeInsets.only(left: 4, bottom: 8),
          child: SelectableText(
            CertificateFields.formatFingerprint(fields.sha256Fingerprint),
            style: theme.textTheme.bodySmall?.copyWith(fontFamily: 'monospace'),
          ),
        ),
        for (final warning in candidate.warnings)
          Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  Icons.warning_amber_rounded,
                  size: 18,
                  color: theme.colorScheme.tertiary,
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    CertificateTexts.warning(l10n, warning),
                    style: theme.textTheme.bodySmall,
                  ),
                ),
              ],
            ),
          ),
        if (selected)
          TextField(
            controller: _aliases[candidate.id],
            decoration: InputDecoration(
              labelText: l10n.certificateAlias,
              isDense: true,
            ),
          ),
      ],
    );
  }

  void _confirm() {
    final requests = <CertificateImportRequest>[];
    for (final candidate in widget.candidates) {
      if (!_selected.contains(candidate.id)) continue;
      requests.add(
        CertificateImportRequest(
          pem: candidate.pem,
          alias: _aliases[candidate.id]?.text.trim() ?? '',
        ),
      );
    }
    widget.onConfirm(requests);
  }
}

class _Banner extends StatelessWidget {
  const _Banner({
    required this.text,
    required this.color,
    required this.textColor,
  });

  final String text;
  final Color color;
  final Color textColor;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Text(
        text,
        style: Theme.of(
          context,
        ).textTheme.bodySmall?.copyWith(color: textColor),
      ),
    );
  }
}
