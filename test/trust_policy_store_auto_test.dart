import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tunnel_forge/core/vpn_protocol.dart';
import 'package:tunnel_forge/features/profiles/data/profile_bridge.dart';
import 'package:tunnel_forge/features/profiles/data/profile_store.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/presentation/profile_editor_sheet.dart';
import 'package:tunnel_forge/features/trust/domain/trust_models.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';

/// The automatic trust mode, from the form's point of view.
///
/// Its whole purpose is that nothing has to be picked, so the two things worth
/// pinning down are that saving works with an empty selection and that no
/// selection is left behind to mean something later.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Future<ProfileStore> buildStore() async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    await prefs.clear();
    return ProfileStore(
      prefsOverride: prefs,
      secretsOverride: MemorySecretStore(),
      backendOverride: MemoryProfileBackend(),
    );
  }

  Future<void> pumpHost(
    WidgetTester tester, {
    required ProfileStore store,
    required String profileId,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: FilledButton(
                onPressed: () {
                  ProfileEditorSheet.show(
                    context,
                    profileId: profileId,
                    store: store,
                  );
                },
                child: const Text('Open'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();
  }

  test('the wire name round-trips and matches the Kotlin enum', () {
    expect(TrustPolicy.storeAuto.wireName, 'STORE_AUTO');
    expect(TrustPolicy.tryFromWire('STORE_AUTO'), TrustPolicy.storeAuto);
    expect(TrustPolicy.tryFromWire('NOT_A_POLICY'), isNull);
  });

  test('a profile carrying the policy survives a JSON round trip', () {
    const profile = Profile(
      id: 'profile-store-auto',
      displayName: 'Office',
      server: 'vpn.example.com',
      user: 'alice',
      protocol: VpnProtocol.sstp,
      trustPolicy: TrustPolicy.storeAuto,
    );

    final restored = Profile.tryFromJson(profile.toJson());

    expect(restored?.trustPolicy, TrustPolicy.storeAuto);
  });

  /// An unknown policy from a newer host must not take a profile down.
  test('an unrecognised policy falls back to system', () {
    const profile = Profile(
      id: 'profile-future',
      displayName: 'Office',
      server: 'vpn.example.com',
      user: 'alice',
      protocol: VpnProtocol.sstp,
    );
    final json = profile.toJson()..['trustPolicy'] = 'SOMETHING_NEWER';

    expect(Profile.tryFromJson(json)?.trustPolicy, TrustPolicy.system);
  });

  testWidgets('the mode offers no certificate picker, only a count', (
    tester,
  ) async {
    final store = await buildStore();
    const profile = Profile(
      id: 'profile-auto-ui',
      displayName: 'Office',
      server: 'vpn.example.com',
      user: 'alice',
      protocol: VpnProtocol.sstp,
      trustPolicy: TrustPolicy.storeAuto,
    );
    await store.upsertProfile(profile, password: 'pw', psk: '');

    await pumpHost(tester, store: store, profileId: profile.id);

    expect(
      find.byKey(const Key('store_auto_certificate_count')),
      findsOneWidget,
    );
    expect(find.text('Trusted certificates'), findsNothing);
  });

  /// The form blocks saving when a chain-building policy has nothing selected.
  /// This mode must not trip that, or it would be unusable for its own purpose.
  testWidgets('saving succeeds with nothing selected', (tester) async {
    final store = await buildStore();
    const profile = Profile(
      id: 'profile-auto-save',
      displayName: 'Office',
      server: 'vpn.example.com',
      user: 'alice',
      protocol: VpnProtocol.sstp,
      trustPolicy: TrustPolicy.storeAuto,
    );
    await store.upsertProfile(profile, password: 'pw', psk: '');

    await pumpHost(tester, store: store, profileId: profile.id);
    await tester.enterText(find.byType(TextField).first, 'Renamed');
    await tester.pump();
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    final saved = (await store.loadProfileWithSecrets(profile.id))?.profile;
    expect(saved?.displayName, 'Renamed');
    expect(saved?.trustPolicy, TrustPolicy.storeAuto);
  });

  /// A selection carried over from another policy is dropped rather than kept
  /// dormant: under this mode it decides nothing, and leaving it would make the
  /// profile claim a dependency it does not have.
  testWidgets('a selection from a previous policy is not kept', (tester) async {
    final store = await buildStore();
    const profile = Profile(
      id: 'profile-auto-clear',
      displayName: 'Office',
      server: 'vpn.example.com',
      user: 'alice',
      protocol: VpnProtocol.sstp,
      trustPolicy: TrustPolicy.storeAuto,
      trustedCertificateIds: <String>['abc123'],
    );
    await store.upsertProfile(profile, password: 'pw', psk: '');

    await pumpHost(tester, store: store, profileId: profile.id);
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    final saved = (await store.loadProfileWithSecrets(profile.id))?.profile;
    expect(saved?.trustedCertificateIds, isEmpty);
    expect(saved?.pinnedFingerprints, isEmpty);
  });
}
