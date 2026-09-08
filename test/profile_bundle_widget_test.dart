import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tunnel_forge/core/vpn_protocol.dart';
import 'package:tunnel_forge/features/profiles/domain/failover_group.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_bundle.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_transfer.dart';
import 'package:tunnel_forge/features/profiles/presentation/profile_bundle_export_sheet.dart';
import 'package:tunnel_forge/features/profiles/presentation/profile_bundle_import_sheet.dart';
import 'package:tunnel_forge/features/profiles/presentation/profile_credentials_dialog.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';

Profile _profile(
  String id,
  String name, {
  String user = 'alice',
  String connectivityCheckUrl = '',
}) {
  return Profile(
    id: id,
    displayName: name,
    server: '${name.toLowerCase()}.acme.example',
    user: user,
    connectivityCheckUrl: connectivityCheckUrl,
    protocol: VpnProtocol.l2tp,
    port: 1701,
    dnsAutomatic: true,
    dns1Host: '',
    dns1Protocol: DnsProtocol.dnsOverUdp,
    dns2Host: '',
    dns2Protocol: DnsProtocol.dnsOverUdp,
  );
}

/// Puts [child] under a button that opens it, which is what a sheet needs.
Widget _host(Future<void> Function(BuildContext) open) {
  return MaterialApp(
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: Builder(
      builder: (context) => Scaffold(
        body: Center(
          child: ElevatedButton(
            onPressed: () => open(context),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  );
}

void main() {
  group('ProfileBundleExportSheet', () {
    testWidgets('refuses a password that is too short or mistyped', (
      tester,
    ) async {
      ProfileBundleExportRequest? result;
      var closed = false;
      await tester.pumpWidget(
        _host((context) async {
          result = await ProfileBundleExportSheet.show(
            context,
            profiles: [_profile('a', 'Amsterdam'), _profile('b', 'Berlin')],
          );
          closed = true;
        }),
      );
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.enterText(
        find.byKey(const Key('profile_set_password')),
        'short',
      );
      // The error messages grow the sheet, so the button can be pushed below
      // the fold between one attempt and the next.
      await tester.ensureVisible(
        find.byKey(const Key('profile_set_export_submit')),
      );
      await tester.tap(find.byKey(const Key('profile_set_export_submit')));
      await tester.pumpAndSettle();
      expect(closed, isFalse, reason: 'a short password must not seal a set');

      await tester.enterText(
        find.byKey(const Key('profile_set_password')),
        'long-enough-password',
      );
      await tester.enterText(
        find.byKey(const Key('profile_set_password_confirm')),
        'long-enough-passwrod',
      );
      // The error messages grow the sheet, so the button can be pushed below
      // the fold between one attempt and the next.
      await tester.ensureVisible(
        find.byKey(const Key('profile_set_export_submit')),
      );
      await tester.tap(find.byKey(const Key('profile_set_export_submit')));
      await tester.pumpAndSettle();
      expect(closed, isFalse, reason: 'a mistyped confirmation must not seal');

      await tester.enterText(
        find.byKey(const Key('profile_set_password_confirm')),
        'long-enough-password',
      );
      // The error messages grow the sheet, so the button can be pushed below
      // the fold between one attempt and the next.
      await tester.ensureVisible(
        find.byKey(const Key('profile_set_export_submit')),
      );
      await tester.tap(find.byKey(const Key('profile_set_export_submit')));
      await tester.pumpAndSettle();

      expect(closed, isTrue);
      expect(result!.password, 'long-enough-password');
      expect(result!.profileIds, ['a', 'b']);
    });

    testWidgets('starts with every profile chosen and lets one be dropped', (
      tester,
    ) async {
      ProfileBundleExportRequest? result;
      await tester.pumpWidget(
        _host((context) async {
          result = await ProfileBundleExportSheet.show(
            context,
            profiles: [_profile('a', 'Amsterdam'), _profile('b', 'Berlin')],
          );
        }),
      );
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('profile_set_pick_b')));
      await tester.enterText(
        find.byKey(const Key('profile_set_password')),
        'long-enough-password',
      );
      await tester.enterText(
        find.byKey(const Key('profile_set_password_confirm')),
        'long-enough-password',
      );
      // The error messages grow the sheet, so the button can be pushed below
      // the fold between one attempt and the next.
      await tester.ensureVisible(
        find.byKey(const Key('profile_set_export_submit')),
      );
      await tester.tap(find.byKey(const Key('profile_set_export_submit')));
      await tester.pumpAndSettle();

      expect(result!.profileIds, ['a']);
    });

    /// The check names a host on the sender's network, so handing it over is a
    /// decision rather than a default — but only when there is one to make.
    testWidgets(
      'the connectivity check is only asked about when there is one',
      (tester) async {
        await tester.pumpWidget(
          _host((context) async {
            await ProfileBundleExportSheet.show(
              context,
              profiles: [_profile('a', 'Amsterdam')],
            );
          }),
        );
        await tester.tap(find.text('open'));
        await tester.pumpAndSettle();

        expect(
          find.byKey(const Key('profile_set_connectivity_check')),
          findsNothing,
        );
      },
    );

    testWidgets('unticking the check leaves it out of the set', (tester) async {
      ProfileBundleExportRequest? result;
      await tester.pumpWidget(
        _host((context) async {
          result = await ProfileBundleExportSheet.show(
            context,
            profiles: [
              _profile(
                'a',
                'Amsterdam',
                connectivityCheckUrl: 'http://probe.acme.internal/',
              ),
            ],
          );
        }),
      );
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('profile_set_connectivity_check')));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const Key('profile_set_password')),
        'long-enough-password',
      );
      await tester.enterText(
        find.byKey(const Key('profile_set_password_confirm')),
        'long-enough-password',
      );
      await tester.ensureVisible(
        find.byKey(const Key('profile_set_export_submit')),
      );
      await tester.tap(find.byKey(const Key('profile_set_export_submit')));
      await tester.pumpAndSettle();

      expect(result!.includeConnectivityCheck, isFalse);
    });

    /// A group is offered whole or not at all: half a failover group is a list
    /// of servers to try that is missing the ones it would fall back to.
    testWidgets('a group whose profile is dropped cannot travel', (
      tester,
    ) async {
      ProfileBundleExportRequest? result;
      await tester.pumpWidget(
        _host((context) async {
          result = await ProfileBundleExportSheet.show(
            context,
            profiles: [_profile('a', 'Amsterdam'), _profile('b', 'Berlin')],
            groups: const [
              FailoverGroup(id: 'g', name: 'Acme', memberIds: ['a', 'b']),
            ],
          );
        }),
      );
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      // Everything is ticked to begin with, the group included; dropping one of
      // its profiles has to drop the group with it.
      await tester.tap(find.byKey(const Key('profile_set_pick_b')));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const Key('profile_set_password')),
        'long-enough-password',
      );
      await tester.enterText(
        find.byKey(const Key('profile_set_password_confirm')),
        'long-enough-password',
      );
      await tester.ensureVisible(
        find.byKey(const Key('profile_set_export_submit')),
      );
      await tester.tap(find.byKey(const Key('profile_set_export_submit')));
      await tester.pumpAndSettle();

      expect(result!.profileIds, ['a']);
      expect(result!.groupIds, isEmpty);
    });

    testWidgets('a group travels when all of its profiles do', (tester) async {
      ProfileBundleExportRequest? result;
      await tester.pumpWidget(
        _host((context) async {
          result = await ProfileBundleExportSheet.show(
            context,
            profiles: [_profile('a', 'Amsterdam'), _profile('b', 'Berlin')],
            groups: const [
              FailoverGroup(id: 'g', name: 'Acme', memberIds: ['a', 'b']),
            ],
          );
        }),
      );
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.enterText(
        find.byKey(const Key('profile_set_password')),
        'long-enough-password',
      );
      await tester.enterText(
        find.byKey(const Key('profile_set_password_confirm')),
        'long-enough-password',
      );
      await tester.ensureVisible(
        find.byKey(const Key('profile_set_export_submit')),
      );
      await tester.tap(find.byKey(const Key('profile_set_export_submit')));
      await tester.pumpAndSettle();

      expect(result!.groupIds, ['g']);
    });
  });

  group('ProfileCredentialsDialog', () {
    /// It autofocuses its first field, so it is always laid out against a
    /// screen with the keyboard already on it. A widget test fails on an
    /// overflow, which is what makes this a regression test rather than a
    /// screenshot someone has to look at.
    testWidgets('fits with the keyboard up on a short screen', (tester) async {
      // Set on the view rather than with a MediaQuery around the page: the
      // dialog is put up by the root navigator, whose MediaQuery comes from the
      // view, so anything wrapped around the page below it is not what the
      // dialog is measured against.
      tester.view.physicalSize = const Size(360, 640);
      tester.view.devicePixelRatio = 1.0;
      tester.view.viewInsets = const FakeViewPadding(bottom: 420);
      addTearDown(tester.view.reset);

      await tester.pumpWidget(
        MaterialApp(
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          // Russian, because that is where it was seen and because its strings
          // are the longer ones: an English-only check would pass on a layout
          // that still overflows for half the people using it.
          locale: const Locale('ru'),
          home: Builder(
            builder: (context) => Scaffold(
              body: Center(
                child: ElevatedButton(
                  onPressed: () => ProfileCredentialsDialog.show(
                    context,
                    profileName: 'Amsterdam',
                  ),
                  child: const Text('open'),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('profile_credentials_user')), findsOneWidget);
      expect(
        find.byKey(const Key('profile_credentials_password')),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    });
  });

  group('ProfileBundleImportSheet', () {
    ProfileBundle bundle() => ProfileBundle(
      name: 'Acme',
      entries: [
        ProfileTransferEnvelope(
          profile: _profile('remote-a', 'Amsterdam', user: ''),
          psk: 'corporate-psk',
        ),
        ProfileTransferEnvelope(
          profile: _profile('remote-b', 'Berlin', user: ''),
          psk: 'corporate-psk',
        ),
      ],
    );

    testWidgets('offers replace for an entry already on the device', (
      tester,
    ) async {
      BundleImportSelection? selection;
      await tester.pumpWidget(
        _host((context) async {
          selection = await ProfileBundleImportSheet.show(
            context,
            bundle: bundle(),
            existing: [_profile('local-a', 'Amsterdam')],
          );
        }),
      );
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      // Only the entry that matches something gets the choice.
      expect(find.byKey(const Key('profile_set_conflict_0')), findsOneWidget);
      expect(find.byKey(const Key('profile_set_conflict_1')), findsNothing);

      await tester.tap(find.byKey(const Key('profile_set_import_submit')));
      await tester.pumpAndSettle();

      expect(selection!.choices, hasLength(2));
      expect(selection!.choices[0].action, BundleImportAction.replace);
      expect(selection!.choices[0].targetProfileId, 'local-a');
      expect(selection!.choices[1].action, BundleImportAction.add);
      expect(selection!.choices[1].targetProfileId, isNull);
    });

    testWidgets('a group can be left behind while its profiles are taken', (
      tester,
    ) async {
      BundleImportSelection? selection;
      final withGroup = ProfileBundle(
        name: 'Acme',
        entries: bundle().entries,
        groups: const [
          BundledFailoverGroup(name: 'Acme', memberIndexes: [0, 1]),
        ],
      );
      await tester.pumpWidget(
        _host((context) async {
          selection = await ProfileBundleImportSheet.show(
            context,
            bundle: withGroup,
            existing: const <Profile>[],
          );
        }),
      );
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      // Ticked to begin with; untick it and the profiles still come.
      await tester.tap(find.byKey(const Key('profile_set_group_0')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('profile_set_import_submit')));
      await tester.pumpAndSettle();

      expect(selection!.groupIndexes, isEmpty);
      expect(selection!.choices, hasLength(2));
      expect(selection!.choices[0].action, BundleImportAction.add);
    });

    testWidgets('a group with every member skipped cannot be chosen', (
      tester,
    ) async {
      BundleImportSelection? selection;
      final withGroup = ProfileBundle(
        name: 'Acme',
        entries: bundle().entries,
        // One member, so the entry it names can be skipped while another
        // entry stays ticked and keeps the submit button alive.
        groups: const [
          BundledFailoverGroup(name: 'Acme', memberIndexes: [1]),
        ],
      );
      await tester.pumpWidget(
        _host((context) async {
          selection = await ProfileBundleImportSheet.show(
            context,
            bundle: withGroup,
            existing: const <Profile>[],
          );
        }),
      );
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('profile_set_entry_1')));
      await tester.pumpAndSettle();

      expect(
        tester
            .widget<CheckboxListTile>(
              find.byKey(const Key('profile_set_group_0')),
            )
            .onChanged,
        isNull,
      );

      await tester.tap(find.byKey(const Key('profile_set_import_submit')));
      await tester.pumpAndSettle();

      expect(selection!.groupIndexes, isEmpty);
    });

    testWidgets('an unticked entry comes back as a skip', (tester) async {
      BundleImportSelection? selection;
      await tester.pumpWidget(
        _host((context) async {
          selection = await ProfileBundleImportSheet.show(
            context,
            bundle: bundle(),
            existing: const <Profile>[],
          );
        }),
      );
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('profile_set_entry_1')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('profile_set_import_submit')));
      await tester.pumpAndSettle();

      expect(selection!.choices[0].action, BundleImportAction.add);
      expect(selection!.choices[1].action, BundleImportAction.skip);
    });
  });
}
