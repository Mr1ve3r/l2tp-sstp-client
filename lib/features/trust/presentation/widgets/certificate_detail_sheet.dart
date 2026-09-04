import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'package:tunnel_forge/features/trust/domain/trust_models.dart';
import 'package:tunnel_forge/features/trust/presentation/widgets/certificate_texts.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';

/// Everything one stored certificate holds, with export and delete.
///
/// The SHA-256 fingerprint is shown in the `AB:CD:...` form and nothing else:
/// that is how a router prints it, and this screen exists so the two can be
/// compared character by character (SPEC 5.9).
class CertificateDetailSheet extends StatelessWidget {
  const CertificateDetailSheet({
    super.key,
    required this.certificate,
    required this.onExport,
    required this.onDelete,
    this.now,
  });

  final ServerCertificate certificate;
  final VoidCallback onExport;
  final VoidCallback onDelete;

  /// Overridable so tests do not depend on the wall clock.
  final DateTime? now;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final fields = certificate.fields;
    final at = now ?? DateTime.now();

    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 28),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              certificate.alias.isEmpty
                  ? fields.displayName
                  : certificate.alias,
              style: theme.textTheme.titleLarge,
            ),
            const SizedBox(height: 4),
            Text(
              CertificateTexts.expiry(l10n, fields, at),
              style: theme.textTheme.bodyMedium?.copyWith(
                color: _expiryColor(theme, fields.expiryAt(at)),
              ),
            ),
            const SizedBox(height: 4),
            Text(
              certificate.usageCount == 0
                  ? l10n.certificateUnused
                  : l10n.certificateUsedByProfiles(certificate.usageCount),
              style: theme.textTheme.bodySmall,
            ),
            const SizedBox(height: 16),
            _Field(label: l10n.certificateSubject, value: fields.subjectDn),
            _Field(label: l10n.certificateIssuer, value: fields.issuerDn),
            _Field(label: l10n.certificateSerial, value: fields.serialNumber),
            _Field(
              label: l10n.certificateValidFrom,
              value: CertificateTexts.formatDate(fields.notBefore),
            ),
            _Field(
              label: l10n.certificateValidUntil,
              value: CertificateTexts.formatDate(fields.notAfter),
            ),
            if (fields.subjectAltNames.isNotEmpty)
              _Field(
                label: l10n.certificateAltNames,
                value: fields.subjectAltNames.join('\n'),
              ),
            if (fields.keyUsage != null)
              _Field(label: l10n.certificateKeyUsage, value: fields.keyUsage!),
            _Field(
              label: l10n.certificateSignature,
              value: fields.signatureAlgorithm,
            ),
            if (fields.publicKeyBits != null)
              _Field(
                label: l10n.certificatePublicKey,
                value: l10n.certificateKeyBits(fields.publicKeyBits!),
              ),
            Wrap(
              spacing: 8,
              children: [
                if (fields.isCa) Chip(label: Text(l10n.certificateIsCa)),
                if (fields.isSelfSigned)
                  Chip(label: Text(l10n.certificateIsSelfSigned)),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              l10n.certificateFingerprintSha256,
              style: theme.textTheme.labelMedium,
            ),
            const SizedBox(height: 4),
            SelectableText(
              CertificateFields.formatFingerprint(fields.sha256Fingerprint),
              style: theme.textTheme.bodyMedium?.copyWith(
                fontFamily: 'monospace',
              ),
            ),
            const SizedBox(height: 4),
            Align(
              alignment: AlignmentDirectional.centerStart,
              child: TextButton.icon(
                onPressed: () => _copyFingerprint(context, l10n, fields),
                icon: const Icon(Icons.copy, size: 18),
                label: Text(l10n.certificateCopyFingerprint),
              ),
            ),
            const Divider(height: 24),
            Row(
              children: [
                TextButton.icon(
                  onPressed: onExport,
                  icon: const Icon(Icons.download, size: 18),
                  label: Text(l10n.certificateExportPem),
                ),
                const Spacer(),
                TextButton.icon(
                  onPressed: () => _confirmDelete(context, l10n),
                  icon: const Icon(Icons.delete_outline, size: 18),
                  style: TextButton.styleFrom(
                    foregroundColor: theme.colorScheme.error,
                  ),
                  label: Text(l10n.delete),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _copyFingerprint(
    BuildContext context,
    AppLocalizations l10n,
    CertificateFields fields,
  ) async {
    await Clipboard.setData(
      ClipboardData(
        text: CertificateFields.formatFingerprint(fields.sha256Fingerprint),
      ),
    );
    if (!context.mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(l10n.certificateCopied)));
  }

  /// Deleting is confirmed, and the confirmation says how many profiles break.
  Future<void> _confirmDelete(
    BuildContext context,
    AppLocalizations l10n,
  ) async {
    final name = certificate.alias.isEmpty
        ? certificate.fields.displayName
        : certificate.alias;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        content: Text(l10n.certificateDeleteConfirm(name)),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(l10n.cancel),
          ),
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(l10n.delete),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    onDelete();
  }

  Color? _expiryColor(ThemeData theme, CertificateExpiry expiry) {
    switch (expiry) {
      case CertificateExpiry.expired:
      case CertificateExpiry.notYetValid:
        return theme.colorScheme.error;
      case CertificateExpiry.expiringSoon:
        return theme.colorScheme.tertiary;
      case CertificateExpiry.valid:
        return null;
    }
  }
}

class _Field extends StatelessWidget {
  const _Field({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: theme.textTheme.labelMedium),
          const SizedBox(height: 2),
          SelectableText(value, style: theme.textTheme.bodyMedium),
        ],
      ),
    );
  }
}
