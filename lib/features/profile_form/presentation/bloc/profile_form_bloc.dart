import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

import 'package:tunnel_forge/core/vpn_protocol.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/trust/domain/trust_models.dart';
import '../../../home/domain/home_models.dart';
import '../../../home/domain/home_repositories.dart';

sealed class ProfileFormEvent extends Equatable {
  const ProfileFormEvent();

  @override
  List<Object?> get props => const [];
}

final class ProfileFormStarted extends ProfileFormEvent {
  const ProfileFormStarted(this.profileId);

  final String? profileId;

  @override
  List<Object?> get props => [profileId];
}

final class ProfileFormDisplayNameChanged extends ProfileFormEvent {
  const ProfileFormDisplayNameChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormServerChanged extends ProfileFormEvent {
  const ProfileFormServerChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormUserChanged extends ProfileFormEvent {
  const ProfileFormUserChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormPasswordChanged extends ProfileFormEvent {
  const ProfileFormPasswordChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormPskChanged extends ProfileFormEvent {
  const ProfileFormPskChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormDnsAutomaticChanged extends ProfileFormEvent {
  const ProfileFormDnsAutomaticChanged(this.value);

  final bool value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormDns1Changed extends ProfileFormEvent {
  const ProfileFormDns1Changed(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormDns1ProtocolChanged extends ProfileFormEvent {
  const ProfileFormDns1ProtocolChanged(this.value);

  final DnsProtocol value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormDns2Changed extends ProfileFormEvent {
  const ProfileFormDns2Changed(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormDns2ProtocolChanged extends ProfileFormEvent {
  const ProfileFormDns2ProtocolChanged(this.value);

  final DnsProtocol value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormMtuChanged extends ProfileFormEvent {
  const ProfileFormMtuChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormProtocolChanged extends ProfileFormEvent {
  const ProfileFormProtocolChanged(this.value);

  final VpnProtocol value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormPortChanged extends ProfileFormEvent {
  const ProfileFormPortChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormTrustPolicyChanged extends ProfileFormEvent {
  const ProfileFormTrustPolicyChanged(this.value);

  final TrustPolicy value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormCertificateToggled extends ProfileFormEvent {
  const ProfileFormCertificateToggled(this.id, this.selected);

  final String id;
  final bool selected;

  @override
  List<Object?> get props => [id, selected];
}

final class ProfileFormExpectedHostnameChanged extends ProfileFormEvent {
  const ProfileFormExpectedHostnameChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormMinTlsVersionChanged extends ProfileFormEvent {
  const ProfileFormMinTlsVersionChanged(this.value);

  final TlsVersion value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormAuthMethodToggled extends ProfileFormEvent {
  const ProfileFormAuthMethodToggled(this.method, this.selected);

  final PppAuthMethod method;
  final bool selected;

  @override
  List<Object?> get props => [method, selected];
}

final class ProfileFormProxyEnabledChanged extends ProfileFormEvent {
  const ProfileFormProxyEnabledChanged(this.value);

  final bool value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormProxyHostChanged extends ProfileFormEvent {
  const ProfileFormProxyHostChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormProxyPortChanged extends ProfileFormEvent {
  const ProfileFormProxyPortChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormProxyUsernameChanged extends ProfileFormEvent {
  const ProfileFormProxyUsernameChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormProxyPasswordChanged extends ProfileFormEvent {
  const ProfileFormProxyPasswordChanged(this.value);

  final String value;

  @override
  List<Object?> get props => [value];
}

final class ProfileFormSaveRequested extends ProfileFormEvent {
  const ProfileFormSaveRequested();
}

class ProfileFormState extends Equatable {
  const ProfileFormState({
    this.profileId = '',
    this.loading = true,
    this.saving = false,
    this.loadError,
    this.displayName = '',
    this.server = '',
    this.user = '',
    this.password = '',
    this.psk = '',
    this.dnsAutomatic = true,
    this.dns1 = '',
    this.dns1Protocol = DnsProtocol.dnsOverUdp,
    this.dns2 = '',
    this.dns2Protocol = DnsProtocol.dnsOverUdp,
    this.mtu = '${Profile.defaultVpnMtu}',
    this.base = const Profile(id: '', displayName: '', server: '', user: ''),
    this.protocol = VpnProtocol.l2tp,
    this.port = '${Profile.defaultSstpPort}',
    this.trustPolicy = TrustPolicy.system,
    this.trustedCertificateIds = const <String>[],
    this.expectedHostname = '',
    this.minTlsVersion = TlsVersion.tls12,
    this.authMethods = PppAuthMethod.defaults,
    this.proxyEnabled = false,
    this.proxyHost = '',
    this.proxyPort = '${Profile.defaultProxyPort}',
    this.proxyUsername = '',
    this.proxyPassword = '',
    this.trustOptions = const TrustOptions(),
    this.messageId = 0,
    this.message,
    this.saved = false,
  });

  final String profileId;
  final bool loading;
  final bool saving;
  final String? loadError;
  final String displayName;
  final String server;
  final String user;
  final String password;
  final String psk;
  final bool dnsAutomatic;
  final String dns1;
  final DnsProtocol dns1Protocol;
  final String dns2;
  final DnsProtocol dns2Protocol;
  final String mtu;

  /// The profile as it was loaded, carrying every field the form does not
  /// draw. Saving edits this rather than building a profile from the visible
  /// fields, which would reset the rest to their defaults.
  final Profile base;

  final VpnProtocol protocol;
  final String port;
  final TrustPolicy trustPolicy;
  final List<String> trustedCertificateIds;
  final String expectedHostname;
  final TlsVersion minTlsVersion;
  final List<PppAuthMethod> authMethods;
  final bool proxyEnabled;
  final String proxyHost;
  final String proxyPort;
  final String proxyUsername;
  final String proxyPassword;

  /// What the certificate store offers this form.
  final TrustOptions trustOptions;

  final int messageId;
  final String? message;
  final bool saved;

  /// The policies this build offers, with a fallback for a host that answered
  /// nothing — a form with no policy to pick is worse than a stale list.
  List<TrustPolicy> get trustPolicies => trustOptions.policies.isEmpty
      ? const [
          TrustPolicy.system,
          TrustPolicy.systemPlusCustom,
          TrustPolicy.customOnly,
          TrustPolicy.pinLeaf,
        ]
      : trustOptions.policies;

  bool get isSstp => protocol == VpnProtocol.sstp;

  /// Trust settings that mean nothing without a selected certificate.
  bool get needsCertificate =>
      trustPolicy == TrustPolicy.customOnly ||
      trustPolicy == TrustPolicy.pinLeaf;

  bool get showsCertificatePicker =>
      needsCertificate || trustPolicy == TrustPolicy.systemPlusCustom;

  String? get portErrorText => _portValidationMessage(port);
  String? get proxyPortErrorText =>
      proxyEnabled ? _portValidationMessage(proxyPort) : null;

  String? get dns1ErrorText =>
      dnsAutomatic ? null : _dnsErrorText('DNS 1', dns1, dns1Protocol);
  String? get dns2ErrorText =>
      dnsAutomatic ? null : _dnsErrorText('DNS 2', dns2, dns2Protocol);
  String? get mtuErrorText => _mtuValidationMessage(mtu);

  ProfileFormState copyWith({
    String? profileId,
    bool? loading,
    bool? saving,
    String? loadError,
    bool clearLoadError = false,
    String? displayName,
    String? server,
    String? user,
    String? password,
    String? psk,
    bool? dnsAutomatic,
    String? dns1,
    DnsProtocol? dns1Protocol,
    String? dns2,
    DnsProtocol? dns2Protocol,
    String? mtu,
    Profile? base,
    VpnProtocol? protocol,
    String? port,
    TrustPolicy? trustPolicy,
    List<String>? trustedCertificateIds,
    String? expectedHostname,
    TlsVersion? minTlsVersion,
    List<PppAuthMethod>? authMethods,
    bool? proxyEnabled,
    String? proxyHost,
    String? proxyPort,
    String? proxyUsername,
    String? proxyPassword,
    TrustOptions? trustOptions,
    int? messageId,
    String? message,
    bool clearMessage = false,
    bool? saved,
  }) {
    return ProfileFormState(
      profileId: profileId ?? this.profileId,
      loading: loading ?? this.loading,
      saving: saving ?? this.saving,
      loadError: clearLoadError ? null : (loadError ?? this.loadError),
      displayName: displayName ?? this.displayName,
      server: server ?? this.server,
      user: user ?? this.user,
      password: password ?? this.password,
      psk: psk ?? this.psk,
      dnsAutomatic: dnsAutomatic ?? this.dnsAutomatic,
      dns1: dns1 ?? this.dns1,
      dns1Protocol: dns1Protocol ?? this.dns1Protocol,
      dns2: dns2 ?? this.dns2,
      dns2Protocol: dns2Protocol ?? this.dns2Protocol,
      mtu: mtu ?? this.mtu,
      base: base ?? this.base,
      protocol: protocol ?? this.protocol,
      port: port ?? this.port,
      trustPolicy: trustPolicy ?? this.trustPolicy,
      trustedCertificateIds:
          trustedCertificateIds ?? this.trustedCertificateIds,
      expectedHostname: expectedHostname ?? this.expectedHostname,
      minTlsVersion: minTlsVersion ?? this.minTlsVersion,
      authMethods: authMethods ?? this.authMethods,
      proxyEnabled: proxyEnabled ?? this.proxyEnabled,
      proxyHost: proxyHost ?? this.proxyHost,
      proxyPort: proxyPort ?? this.proxyPort,
      proxyUsername: proxyUsername ?? this.proxyUsername,
      proxyPassword: proxyPassword ?? this.proxyPassword,
      trustOptions: trustOptions ?? this.trustOptions,
      messageId: messageId ?? this.messageId,
      message: clearMessage ? null : (message ?? this.message),
      saved: saved ?? this.saved,
    );
  }

  @override
  List<Object?> get props => [
    profileId,
    loading,
    saving,
    loadError,
    displayName,
    server,
    user,
    password,
    psk,
    dnsAutomatic,
    dns1,
    dns1Protocol,
    dns2,
    dns2Protocol,
    mtu,
    base,
    protocol,
    port,
    trustedCertificateIds,
    trustPolicy,
    expectedHostname,
    minTlsVersion,
    authMethods,
    proxyEnabled,
    proxyHost,
    proxyPort,
    proxyUsername,
    proxyPassword,
    trustOptions,
    messageId,
    message,
    saved,
  ];
}

const int _kMinPort = 1;
const int _kMaxPort = 65535;

String? _portValidationMessage(String value) {
  final parsed = int.tryParse(value.trim());
  if (parsed == null || parsed < _kMinPort || parsed > _kMaxPort) {
    return AppText.current.portRangeError(_kMinPort, _kMaxPort);
  }
  return null;
}

String? _mtuValidationMessage(String value) {
  final normalized = value.trim();
  if (normalized.isEmpty) {
    return AppText.current.enterMtuValue;
  }
  final mtuParsed = int.tryParse(normalized);
  if (mtuParsed == null) {
    return AppText.current.mtuMustBeWholeNumber;
  }
  if (mtuParsed < Profile.minVpnMtu || mtuParsed > Profile.maxVpnMtu) {
    return AppText.current.mtuMustBeBetween(
      Profile.minVpnMtu,
      Profile.maxVpnMtu,
    );
  }
  return null;
}

class ProfileFormBloc extends Bloc<ProfileFormEvent, ProfileFormState> {
  ProfileFormBloc(this._profilesRepository) : super(const ProfileFormState()) {
    on<ProfileFormStarted>(_onStarted);
    on<ProfileFormDisplayNameChanged>(
      (event, emit) =>
          emit(state.copyWith(displayName: event.value, saved: false)),
    );
    on<ProfileFormServerChanged>(
      (event, emit) => emit(state.copyWith(server: event.value, saved: false)),
    );
    on<ProfileFormUserChanged>(
      (event, emit) => emit(state.copyWith(user: event.value, saved: false)),
    );
    on<ProfileFormPasswordChanged>(
      (event, emit) =>
          emit(state.copyWith(password: event.value, saved: false)),
    );
    on<ProfileFormPskChanged>(
      (event, emit) => emit(state.copyWith(psk: event.value, saved: false)),
    );
    on<ProfileFormDnsAutomaticChanged>(
      (event, emit) =>
          emit(state.copyWith(dnsAutomatic: event.value, saved: false)),
    );
    on<ProfileFormDns1Changed>(
      (event, emit) => emit(state.copyWith(dns1: event.value, saved: false)),
    );
    on<ProfileFormDns1ProtocolChanged>(
      (event, emit) =>
          emit(state.copyWith(dns1Protocol: event.value, saved: false)),
    );
    on<ProfileFormDns2Changed>(
      (event, emit) => emit(state.copyWith(dns2: event.value, saved: false)),
    );
    on<ProfileFormDns2ProtocolChanged>(
      (event, emit) =>
          emit(state.copyWith(dns2Protocol: event.value, saved: false)),
    );
    on<ProfileFormMtuChanged>(
      (event, emit) =>
          emit(state.copyWith(mtu: event.value.trim(), saved: false)),
    );
    on<ProfileFormProtocolChanged>(
      (event, emit) =>
          emit(state.copyWith(protocol: event.value, saved: false)),
    );
    on<ProfileFormPortChanged>(
      (event, emit) =>
          emit(state.copyWith(port: event.value.trim(), saved: false)),
    );
    on<ProfileFormTrustPolicyChanged>(
      (event, emit) =>
          emit(state.copyWith(trustPolicy: event.value, saved: false)),
    );
    on<ProfileFormCertificateToggled>((event, emit) {
      final next = [...state.trustedCertificateIds]..remove(event.id);
      if (event.selected) next.add(event.id);
      emit(state.copyWith(trustedCertificateIds: next, saved: false));
    });
    on<ProfileFormExpectedHostnameChanged>(
      (event, emit) =>
          emit(state.copyWith(expectedHostname: event.value, saved: false)),
    );
    on<ProfileFormMinTlsVersionChanged>(
      (event, emit) =>
          emit(state.copyWith(minTlsVersion: event.value, saved: false)),
    );
    on<ProfileFormAuthMethodToggled>((event, emit) {
      // Kept in PppAuthMethod.values order, which is the preference order the
      // engine offers them in.
      final next = PppAuthMethod.values
          .where(
            (method) => method == event.method
                ? event.selected
                : state.authMethods.contains(method),
          )
          .toList(growable: false);
      emit(state.copyWith(authMethods: next, saved: false));
    });
    on<ProfileFormProxyEnabledChanged>(
      (event, emit) =>
          emit(state.copyWith(proxyEnabled: event.value, saved: false)),
    );
    on<ProfileFormProxyHostChanged>(
      (event, emit) =>
          emit(state.copyWith(proxyHost: event.value, saved: false)),
    );
    on<ProfileFormProxyPortChanged>(
      (event, emit) =>
          emit(state.copyWith(proxyPort: event.value.trim(), saved: false)),
    );
    on<ProfileFormProxyUsernameChanged>(
      (event, emit) =>
          emit(state.copyWith(proxyUsername: event.value, saved: false)),
    );
    on<ProfileFormProxyPasswordChanged>(
      (event, emit) =>
          emit(state.copyWith(proxyPassword: event.value, saved: false)),
    );
    on<ProfileFormSaveRequested>(_onSaveRequested);
  }

  final ProfilesRepository _profilesRepository;

  Future<void> _onStarted(
    ProfileFormStarted event,
    Emitter<ProfileFormState> emit,
  ) async {
    final profileId = event.profileId;
    final trustOptions = await _loadTrustOptions();
    if (profileId == null) {
      final id = _profilesRepository.newProfileId();
      emit(
        state.copyWith(
          base: Profile(
            id: id,
            displayName: '',
            server: '',
            user: '',
            createdAt: DateTime.now().millisecondsSinceEpoch,
          ),
          trustOptions: trustOptions,
          profileId: id,
          loading: false,
          clearLoadError: true,
          displayName: AppText.current.newProfile,
          server: 'vpn.example.com',
          user: '',
          password: '',
          psk: '',
          dnsAutomatic: true,
          dns1: '',
          dns1Protocol: DnsProtocol.dnsOverUdp,
          dns2: '',
          dns2Protocol: DnsProtocol.dnsOverUdp,
          mtu: '${Profile.defaultVpnMtu}',
          protocol: VpnProtocol.l2tp,
          port: '${Profile.defaultSstpPort}',
          trustPolicy: TrustPolicy.system,
          trustedCertificateIds: const <String>[],
          expectedHostname: '',
          minTlsVersion: TlsVersion.tls12,
          authMethods: PppAuthMethod.defaults,
          proxyEnabled: false,
          proxyHost: '',
          proxyPort: '${Profile.defaultProxyPort}',
          proxyUsername: '',
          proxyPassword: '',
          saved: false,
        ),
      );
      return;
    }
    final row = await _profilesRepository.loadProfileWithSecrets(profileId);
    if (row == null) {
      emit(
        state.copyWith(
          profileId: profileId,
          loading: false,
          loadError: AppText.current.profileNoLongerExists,
        ),
      );
      return;
    }
    final profile = row.profile;
    emit(
      state.copyWith(
        base: profile,
        trustOptions: trustOptions,
        profileId: profile.id,
        loading: false,
        clearLoadError: true,
        displayName: profile.displayName,
        server: profile.server,
        user: profile.user,
        password: row.password,
        psk: row.psk,
        dnsAutomatic: profile.dnsAutomatic,
        dns1: profile.dns1Host,
        dns1Protocol: profile.dns1Protocol,
        dns2: profile.dns2Host,
        dns2Protocol: profile.dns2Protocol,
        mtu: '${profile.mtu}',
        protocol: profile.protocol,
        port: '${profile.port}',
        trustPolicy: profile.trustPolicy,
        trustedCertificateIds: profile.trustPolicy == TrustPolicy.pinLeaf
            ? profile.pinnedFingerprints
            : profile.trustedCertificateIds,
        expectedHostname: profile.expectedHostname,
        minTlsVersion: profile.minTlsVersion,
        authMethods: profile.pppAuthMethods,
        proxyEnabled: profile.proxyEnabled,
        proxyHost: profile.proxyHost,
        proxyPort: '${profile.proxyPort}',
        proxyUsername: profile.proxyUsername,
        proxyPassword: row.proxyPassword,
      ),
    );
  }

  /// The certificate store may be absent (tests) or fail; the form still opens.
  Future<TrustOptions> _loadTrustOptions() async {
    try {
      return await _profilesRepository.loadTrustOptions();
    } catch (_) {
      return const TrustOptions();
    }
  }

  Future<void> _onSaveRequested(
    ProfileFormSaveRequested event,
    Emitter<ProfileFormState> emit,
  ) async {
    final serverTrim = state.server.trim();
    if (serverTrim.isEmpty) {
      emit(_messageState(AppText.current.enterServerAddress));
      return;
    }
    final mtuErrorText = state.mtuErrorText;
    if (mtuErrorText != null) {
      emit(_messageState(mtuErrorText));
      return;
    }
    final mtuParsed = int.parse(state.mtu.trim());
    if (state.isSstp) {
      final portErrorText = state.portErrorText;
      if (portErrorText != null) {
        emit(_messageState(portErrorText));
        return;
      }
      if (state.authMethods.isEmpty) {
        emit(_messageState(AppText.current.selectOneAuthMethod));
        return;
      }
      if (state.needsCertificate && state.trustedCertificateIds.isEmpty) {
        emit(_messageState(AppText.current.selectOneCertificate));
        return;
      }
      if (state.proxyEnabled) {
        if (state.proxyHost.trim().isEmpty) {
          emit(_messageState(AppText.current.enterProxyHost));
          return;
        }
        final proxyPortErrorText = state.proxyPortErrorText;
        if (proxyPortErrorText != null) {
          emit(_messageState(proxyPortErrorText));
          return;
        }
      }
    }
    if (!state.dnsAutomatic) {
      final invalidDns1 = Profile.invalidDnsServer(
        state.dns1,
        state.dns1Protocol,
      );
      if (invalidDns1 != null) {
        emit(
          _messageState(
            Profile.validationMessageForDnsServer(
              'DNS 1',
              state.dns1,
              state.dns1Protocol,
            ),
          ),
        );
        return;
      }
      final invalidDns2 = Profile.invalidDnsServer(
        state.dns2,
        state.dns2Protocol,
      );
      if (invalidDns2 != null) {
        emit(
          _messageState(
            Profile.validationMessageForDnsServer(
              'DNS 2',
              state.dns2,
              state.dns2Protocol,
            ),
          ),
        );
        return;
      }
      if (Profile.orderedDnsServers(
        dns1Host: state.dns1,
        dns1Protocol: state.dns1Protocol,
        dns2Host: state.dns2,
        dns2Protocol: state.dns2Protocol,
      ).isEmpty) {
        emit(_messageState(AppText.current.enterAtLeastOneDnsServer));
        return;
      }
    }

    emit(state.copyWith(saving: true, clearMessage: true, saved: false));
    try {
      // Edit what was loaded rather than build a new profile: the form draws
      // some of the fields, and the rest have to survive a save untouched.
      final profile = state.base.copyWith(
        id: state.profileId,
        displayName: state.displayName.trim().isEmpty
            ? serverTrim
            : state.displayName.trim(),
        server: serverTrim,
        user: state.user,
        dnsAutomatic: state.dnsAutomatic,
        dns1Host: Profile.normalizeDnsServerForProtocol(
          state.dns1,
          state.dns1Protocol,
        ),
        dns1Protocol: state.dns1Protocol,
        dns2Host: Profile.normalizeDnsServerForProtocol(
          state.dns2,
          state.dns2Protocol,
        ),
        dns2Protocol: state.dns2Protocol,
        mtu: Profile.normalizeMtu(mtuParsed),
        protocol: state.protocol,
        port: int.tryParse(state.port.trim()) ?? Profile.defaultSstpPort,
        trustPolicy: state.trustPolicy,
        // The store keeps certificates and pins apart; the form has one
        // selection list, and where it lands is decided by the policy.
        trustedCertificateIds: state.trustPolicy == TrustPolicy.pinLeaf
            ? const <String>[]
            : state.trustedCertificateIds,
        pinnedFingerprints: state.trustPolicy == TrustPolicy.pinLeaf
            ? state.trustedCertificateIds
            : const <String>[],
        expectedHostname: state.expectedHostname.trim(),
        minTlsVersion: state.minTlsVersion,
        pppAuthMethods: state.authMethods,
        proxyEnabled: state.proxyEnabled,
        proxyHost: state.proxyHost.trim(),
        proxyPort:
            int.tryParse(state.proxyPort.trim()) ?? Profile.defaultProxyPort,
        proxyUsername: state.proxyUsername.trim(),
      );
      await _profilesRepository.upsertProfile(
        profile,
        password: state.password,
        psk: state.psk,
        proxyPassword: state.proxyEnabled ? state.proxyPassword : '',
      );
      emit(state.copyWith(saving: false, saved: true));
    } catch (_) {
      emit(_messageState(AppText.current.couldNotSaveChanges, saving: false));
    }
  }

  ProfileFormState _messageState(String message, {bool? saving}) {
    return state.copyWith(
      saving: saving ?? false,
      messageId: state.messageId + 1,
      message: message,
      saved: false,
    );
  }
}

String? _dnsErrorText(String label, String value, DnsProtocol protocol) {
  final invalid = Profile.invalidDnsServer(value, protocol);
  if (invalid == null) return null;
  return Profile.validationMessageForDnsServer(label, value, protocol);
}
