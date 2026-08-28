import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import 'package:tunnel_forge/features/trust/domain/trust_models.dart';
import 'package:tunnel_forge/features/trust/domain/trust_repository.dart';

sealed class CertificatesEvent extends Equatable {
  const CertificatesEvent();

  @override
  List<Object?> get props => const [];
}

final class CertificatesStarted extends CertificatesEvent {
  const CertificatesStarted();
}

/// Import path A: pick a file through the system document picker.
final class CertificatesFilePickRequested extends CertificatesEvent {
  const CertificatesFilePickRequested();
}

/// Import path B: read certificates out of pasted text.
final class CertificatesPemTextSubmitted extends CertificatesEvent {
  const CertificatesPemTextSubmitted(this.text);

  final String text;

  @override
  List<Object?> get props => [text];
}

/// Import path C: download the chain a server presents.
final class CertificatesServerChainRequested extends CertificatesEvent {
  const CertificatesServerChainRequested({
    required this.host,
    required this.port,
  });

  final String host;
  final int port;

  @override
  List<Object?> get props => [host, port];
}

/// The user accepted some of the offered certificates.
final class CertificatesImportConfirmed extends CertificatesEvent {
  const CertificatesImportConfirmed(this.requests);

  final List<CertificateImportRequest> requests;

  @override
  List<Object?> get props => [requests];
}

/// The user closed the import sheet without keeping anything.
final class CertificatesImportDismissed extends CertificatesEvent {
  const CertificatesImportDismissed();
}

final class CertificatesDeleteRequested extends CertificatesEvent {
  const CertificatesDeleteRequested(this.id);

  final String id;

  @override
  List<Object?> get props => [id];
}

final class CertificatesRenameRequested extends CertificatesEvent {
  const CertificatesRenameRequested({required this.id, required this.alias});

  final String id;
  final String alias;

  @override
  List<Object?> get props => [id, alias];
}

final class CertificatesExportRequested extends CertificatesEvent {
  const CertificatesExportRequested(this.id);

  final String id;

  @override
  List<Object?> get props => [id];
}

/// The screen has shown whatever was last reported.
final class CertificatesNoticeCleared extends CertificatesEvent {
  const CertificatesNoticeCleared();
}

/// What the certificate screen shows.
///
/// [candidates] is what makes an import two steps: the host reads certificates
/// and describes them, the user sees the fingerprints and warnings, and only
/// then does anything reach the store.
class CertificatesState extends Equatable {
  const CertificatesState({
    this.loading = true,
    this.busy = false,
    this.certificates = const <ServerCertificate>[],
    this.policies = const <TrustPolicy>[],
    this.candidates = const <CertificateCandidate>[],
    this.failure,
    this.exportedPem,
    this.importedCount,
  });

  final bool loading;

  /// An operation is running. The screen stays visible and interaction waits.
  final bool busy;
  final List<ServerCertificate> certificates;

  /// Trust policies this build offers (SPEC 5.5).
  final List<TrustPolicy> policies;

  /// Certificates offered for import, awaiting the user's choice.
  final List<CertificateCandidate> candidates;
  final TrustFailure? failure;

  /// PEM of a certificate the user asked to export, for copying out.
  final String? exportedPem;

  /// How many certificates the last import stored.
  final int? importedCount;

  bool get isEmpty => !loading && certificates.isEmpty;

  CertificatesState copyWith({
    bool? loading,
    bool? busy,
    List<ServerCertificate>? certificates,
    List<TrustPolicy>? policies,
    List<CertificateCandidate>? candidates,
    TrustFailure? failure,
    String? exportedPem,
    int? importedCount,
    bool clearFailure = false,
    bool clearExportedPem = false,
    bool clearImportedCount = false,
  }) {
    return CertificatesState(
      loading: loading ?? this.loading,
      busy: busy ?? this.busy,
      certificates: certificates ?? this.certificates,
      policies: policies ?? this.policies,
      candidates: candidates ?? this.candidates,
      failure: clearFailure ? null : (failure ?? this.failure),
      exportedPem: clearExportedPem ? null : (exportedPem ?? this.exportedPem),
      importedCount: clearImportedCount
          ? null
          : (importedCount ?? this.importedCount),
    );
  }

  @override
  List<Object?> get props => [
    loading,
    busy,
    certificates,
    policies,
    candidates,
    failure,
    exportedPem,
    importedCount,
  ];
}

/// Drives the server certificates screen (SPEC 5.9).
class CertificatesBloc extends Bloc<CertificatesEvent, CertificatesState> {
  CertificatesBloc(this._repository) : super(const CertificatesState()) {
    on<CertificatesStarted>(_onStarted);
    on<CertificatesFilePickRequested>(_onFilePickRequested);
    on<CertificatesPemTextSubmitted>(_onPemTextSubmitted);
    on<CertificatesServerChainRequested>(_onServerChainRequested);
    on<CertificatesImportConfirmed>(_onImportConfirmed);
    on<CertificatesImportDismissed>(_onImportDismissed);
    on<CertificatesDeleteRequested>(_onDeleteRequested);
    on<CertificatesRenameRequested>(_onRenameRequested);
    on<CertificatesExportRequested>(_onExportRequested);
    on<CertificatesNoticeCleared>(_onNoticeCleared);
  }

  final CertificatesRepository _repository;

  Future<void> _onStarted(
    CertificatesStarted event,
    Emitter<CertificatesState> emit,
  ) async {
    emit(state.copyWith(loading: true, clearFailure: true));
    try {
      final certificates = await _repository.list();
      final policies = await _repository.policies();
      emit(
        state.copyWith(
          loading: false,
          certificates: certificates,
          policies: policies,
        ),
      );
    } on TrustFailure catch (e) {
      emit(state.copyWith(loading: false, failure: e));
    }
  }

  Future<void> _onFilePickRequested(
    CertificatesFilePickRequested event,
    Emitter<CertificatesState> emit,
  ) async {
    await _offer(emit, () => _repository.pickFile());
  }

  Future<void> _onPemTextSubmitted(
    CertificatesPemTextSubmitted event,
    Emitter<CertificatesState> emit,
  ) async {
    await _offer(emit, () => _repository.parsePem(event.text));
  }

  Future<void> _onServerChainRequested(
    CertificatesServerChainRequested event,
    Emitter<CertificatesState> emit,
  ) async {
    await _offer(
      emit,
      () => _repository.fetchChain(host: event.host, port: event.port),
    );
  }

  /// Runs one of the three import paths and shows what it found.
  ///
  /// A `null` result is the user backing out of the document picker: not a
  /// failure, and the screen should look untouched afterwards.
  Future<void> _offer(
    Emitter<CertificatesState> emit,
    Future<List<CertificateCandidate>?> Function() read,
  ) async {
    emit(state.copyWith(busy: true, clearFailure: true));
    try {
      final candidates = await read();
      emit(
        state.copyWith(
          busy: false,
          candidates: candidates ?? const <CertificateCandidate>[],
        ),
      );
    } on TrustFailure catch (e) {
      emit(state.copyWith(busy: false, failure: e));
    }
  }

  Future<void> _onImportConfirmed(
    CertificatesImportConfirmed event,
    Emitter<CertificatesState> emit,
  ) async {
    if (event.requests.isEmpty) {
      emit(state.copyWith(candidates: const <CertificateCandidate>[]));
      return;
    }
    emit(state.copyWith(busy: true, clearFailure: true));
    try {
      final stored = await _repository.import(event.requests);
      emit(
        state.copyWith(
          busy: false,
          candidates: const <CertificateCandidate>[],
          certificates: await _repository.list(),
          importedCount: stored.length,
        ),
      );
    } on TrustFailure catch (e) {
      emit(state.copyWith(busy: false, failure: e));
    }
  }

  void _onImportDismissed(
    CertificatesImportDismissed event,
    Emitter<CertificatesState> emit,
  ) {
    emit(state.copyWith(candidates: const <CertificateCandidate>[]));
  }

  Future<void> _onDeleteRequested(
    CertificatesDeleteRequested event,
    Emitter<CertificatesState> emit,
  ) async {
    await _mutate(emit, () => _repository.delete(event.id));
  }

  Future<void> _onRenameRequested(
    CertificatesRenameRequested event,
    Emitter<CertificatesState> emit,
  ) async {
    await _mutate(emit, () => _repository.rename(event.id, event.alias));
  }

  Future<void> _mutate(
    Emitter<CertificatesState> emit,
    Future<void> Function() change,
  ) async {
    emit(state.copyWith(busy: true, clearFailure: true));
    try {
      await change();
      emit(state.copyWith(busy: false, certificates: await _repository.list()));
    } on TrustFailure catch (e) {
      emit(state.copyWith(busy: false, failure: e));
    }
  }

  Future<void> _onExportRequested(
    CertificatesExportRequested event,
    Emitter<CertificatesState> emit,
  ) async {
    emit(
      state.copyWith(busy: true, clearFailure: true, clearExportedPem: true),
    );
    try {
      final pem = await _repository.exportPem(event.id);
      emit(state.copyWith(busy: false, exportedPem: pem));
    } on TrustFailure catch (e) {
      emit(state.copyWith(busy: false, failure: e));
    }
  }

  void _onNoticeCleared(
    CertificatesNoticeCleared event,
    Emitter<CertificatesState> emit,
  ) {
    emit(
      state.copyWith(
        clearFailure: true,
        clearExportedPem: true,
        clearImportedCount: true,
      ),
    );
  }
}
