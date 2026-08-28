import 'package:flutter/services.dart';

import 'package:tunnel_forge/features/trust/data/trust_contract.dart';
import 'package:tunnel_forge/features/trust/domain/trust_models.dart';

/// The certificate store, as reached from Flutter (SPEC phase 5).
///
/// The store itself is on the host: certificates are read, written and turned
/// into trust managers there, and this side holds only what it displays. Every
/// failure arrives as a [TrustFailure] with the host's code, so the UI can say
/// "that file is not a certificate" rather than "PlatformException".
class TrustBridge {
  TrustBridge({MethodChannel? channel})
    : _channel = channel ?? const MethodChannel(TrustContract.channel);

  final MethodChannel _channel;

  /// Every stored certificate, newest import first.
  Future<List<ServerCertificate>> listCertificates() async {
    final raw = await _invoke<List<Object?>>(TrustContract.listCertificates);
    return _certificates(raw);
  }

  /// Which trust policies this build offers (SPEC 5.5).
  ///
  /// Asked rather than assumed: `INSECURE` is absent from a release build, and
  /// a list hardcoded here would go stale the moment that changes.
  Future<List<TrustPolicy>> listTrustPolicies() async {
    final raw = await _invoke<List<Object?>>(TrustContract.listTrustPolicies);
    return (raw ?? const <Object?>[])
        .whereType<String>()
        .map(TrustPolicy.tryFromWire)
        .whereType<TrustPolicy>()
        .toList(growable: false);
  }

  /// Opens the system document picker (SPEC 5.3 A).
  ///
  /// Returns `null` when the user backed out, which is not a failure and
  /// should leave the screen as it was.
  Future<List<CertificateCandidate>?> pickCertificateFile() async {
    final raw = await _invoke<List<Object?>>(TrustContract.pickCertificateFile);
    if (raw == null) return null;
    return _candidates(raw);
  }

  /// Reads certificates out of text the user pasted (SPEC 5.3 B).
  Future<List<CertificateCandidate>> parsePemText(String text) async {
    final raw = await _invoke<List<Object?>>(
      TrustContract.parsePemText,
      <String, Object?>{TrustContract.argText: text},
    );
    return _candidates(raw);
  }

  /// Downloads the chain a server presents, leaf first (SPEC 5.3 C).
  Future<List<CertificateCandidate>> fetchServerChain({
    required String host,
    required int port,
  }) async {
    final raw = await _invoke<List<Object?>>(
      TrustContract.fetchServerChain,
      <String, Object?>{
        TrustContract.argHost: host,
        TrustContract.argPort: port,
      },
    );
    return _candidates(raw);
  }

  /// Stores the candidates the user accepted.
  Future<List<ServerCertificate>> importCertificates(
    List<CertificateImportRequest> requests,
  ) async {
    final raw = await _invoke<List<Object?>>(
      TrustContract.importCertificates,
      <String, Object?>{
        TrustContract.argCertificates: requests
            .map((request) => request.toMap())
            .toList(growable: false),
      },
    );
    return _certificates(raw);
  }

  /// Removes a certificate, its file and every profile's reference to it.
  Future<bool> deleteCertificate(String id) async {
    final removed = await _invoke<bool>(
      TrustContract.deleteCertificate,
      <String, Object?>{TrustContract.argId: id},
    );
    return removed ?? false;
  }

  Future<bool> renameCertificate(String id, String alias) async {
    final renamed = await _invoke<bool>(
      TrustContract.renameCertificate,
      <String, Object?>{TrustContract.argId: id, TrustContract.argAlias: alias},
    );
    return renamed ?? false;
  }

  /// The stored PEM, for copying out or saving elsewhere.
  Future<String?> exportCertificate(String id) {
    return _invoke<String>(TrustContract.exportCertificate, <String, Object?>{
      TrustContract.argId: id,
    });
  }

  Future<T?> _invoke<T>(
    String method, [
    Map<String, Object?>? arguments,
  ]) async {
    try {
      return await _channel.invokeMethod<T>(method, arguments);
    } on PlatformException catch (e) {
      throw TrustFailure(e.code, e.message);
    } on MissingPluginException catch (e) {
      // The host side of this channel is Android-only. A build without it
      // should report that plainly rather than crash a screen.
      throw TrustFailure(TrustContract.errorReadFailed, e.message);
    }
  }

  List<ServerCertificate> _certificates(List<Object?>? raw) {
    return (raw ?? const <Object?>[])
        .whereType<Map<Object?, Object?>>()
        .map(ServerCertificate.fromMap)
        .toList(growable: false);
  }

  List<CertificateCandidate> _candidates(List<Object?>? raw) {
    return (raw ?? const <Object?>[])
        .whereType<Map<Object?, Object?>>()
        .map(CertificateCandidate.fromMap)
        .toList(growable: false);
  }
}
