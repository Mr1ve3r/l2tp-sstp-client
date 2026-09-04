// The failover group editor (SPEC 10.1.1): what a user can build with it, and
// what it refuses to hand back.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:tunnel_forge/core/vpn_protocol.dart';
import 'package:tunnel_forge/features/profiles/domain/failover_group.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/presentation/failover_group_editor.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final profiles = [
    _profile('l2tp', 'Work L2TP', VpnProtocol.l2tp),
    _profile('sstp', 'Work SSTP', VpnProtocol.sstp),
    _profile('spare', 'Spare', VpnProtocol.l2tp),
  ];

  Future<List<FailoverGroup>> pumpEditor(
    WidgetTester tester, {
    FailoverGroup? group,
  }) async {
    final saved = <FailoverGroup>[];
    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: Scaffold(
          body: FailoverGroupEditorView(
            group: group,
            profiles: profiles,
            onClose: () {},
            onSave: saved.add,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return saved;
  }

  Future<void> addMember(WidgetTester tester, String id) async {
    await tester.tap(find.byKey(const Key('failover_group_add_member')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(Key('failover_candidate_$id')));
    await tester.pumpAndSettle();
  }

  Future<void> chooseMemberAction(
    WidgetTester tester,
    String id,
    String label,
  ) async {
    await tester.tap(find.byKey(Key('failover_member_actions_$id')));
    await tester.pumpAndSettle();
    await tester.tap(find.text(label).last);
    await tester.pumpAndSettle();
  }

  testWidgets('a new group is built from profiles and saved in order', (
    tester,
  ) async {
    final saved = await pumpEditor(tester);

    await tester.enterText(
      find.byKey(const Key('failover_group_name')),
      'Work',
    );
    await addMember(tester, 'l2tp');
    await addMember(tester, 'sstp');
    await tester.tap(find.byKey(const Key('failover_group_editor_save')));
    await tester.pumpAndSettle();

    expect(saved, hasLength(1));
    expect(saved.single.name, 'Work');
    expect(saved.single.id, isEmpty);
    expect(saved.single.memberIds, ['l2tp', 'sstp']);
    expect(
      saved.single.connectTimeoutSec,
      FailoverGroup.defaultConnectTimeoutSec,
    );
  });

  testWidgets('a group with no profiles is not handed back', (tester) async {
    final saved = await pumpEditor(tester);

    await tester.enterText(
      find.byKey(const Key('failover_group_name')),
      'Work',
    );
    await tester.tap(find.byKey(const Key('failover_group_editor_save')));
    await tester.pumpAndSettle();

    expect(saved, isEmpty);
    expect(find.text('Add at least one profile to the group.'), findsOneWidget);
  });

  testWidgets('an unnamed group is not handed back', (tester) async {
    final saved = await pumpEditor(tester);

    await addMember(tester, 'l2tp');
    await tester.tap(find.byKey(const Key('failover_group_editor_save')));
    await tester.pumpAndSettle();

    expect(saved, isEmpty);
    expect(find.text('Give the group a name.'), findsOneWidget);
  });

  testWidgets('a budget this build will not run is refused before the store', (
    tester,
  ) async {
    final saved = await pumpEditor(tester);

    await tester.enterText(
      find.byKey(const Key('failover_group_name')),
      'Work',
    );
    await addMember(tester, 'l2tp');
    await tester.enterText(
      find.byKey(const Key('failover_group_connect_timeout')),
      '1',
    );
    await tester.tap(find.byKey(const Key('failover_group_editor_save')));
    await tester.pumpAndSettle();

    expect(saved, isEmpty);
    expect(find.text('Choose between 5 and 120 seconds.'), findsOneWidget);

    await tester.enterText(
      find.byKey(const Key('failover_group_connect_timeout')),
      '30',
    );
    await tester.tap(find.byKey(const Key('failover_group_editor_save')));
    await tester.pumpAndSettle();

    expect(saved, hasLength(1));
    expect(saved.single.connectTimeoutSec, 30);
  });

  testWidgets('members move up and down, and the order is what is saved', (
    tester,
  ) async {
    final saved = await pumpEditor(
      tester,
      group: const FailoverGroup(
        id: 'g1',
        name: 'Work',
        createdAt: 7,
        connectTimeoutSec: 20,
        memberIds: ['l2tp', 'sstp', 'spare'],
      ),
    );

    // The last member moving up, and the first moving down, meet in the middle.
    await chooseMemberAction(tester, 'spare', 'Move up');
    await chooseMemberAction(tester, 'l2tp', 'Move down');
    await tester.tap(find.byKey(const Key('failover_group_editor_save')));
    await tester.pumpAndSettle();

    expect(saved.single.memberIds, ['spare', 'l2tp', 'sstp']);
    // Editing keeps the identity and the creation time the list is ordered by.
    expect(saved.single.id, 'g1');
    expect(saved.single.createdAt, 7);
    expect(saved.single.connectTimeoutSec, 20);
  });

  testWidgets('a removed member is offered again and is not saved', (
    tester,
  ) async {
    final saved = await pumpEditor(
      tester,
      group: const FailoverGroup(
        id: 'g1',
        name: 'Work',
        memberIds: ['l2tp', 'sstp'],
      ),
    );

    await chooseMemberAction(tester, 'sstp', 'Remove from the group');
    await tester.tap(find.byKey(const Key('failover_group_add_member')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('failover_candidate_sstp')), findsOneWidget);
    await tester.tap(find.byKey(const Key('failover_candidate_spare')));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('failover_group_editor_save')));
    await tester.pumpAndSettle();

    expect(saved.single.memberIds, ['l2tp', 'spare']);
  });

  testWidgets('a member whose profile is gone is dropped as the form opens', (
    tester,
  ) async {
    final saved = await pumpEditor(
      tester,
      group: const FailoverGroup(
        id: 'g1',
        name: 'Work',
        memberIds: ['l2tp', 'deleted-elsewhere'],
      ),
    );

    expect(find.byKey(const Key('failover_member_l2tp')), findsOneWidget);
    expect(
      find.byKey(const Key('failover_member_deleted-elsewhere')),
      findsNothing,
    );

    await tester.tap(find.byKey(const Key('failover_group_editor_save')));
    await tester.pumpAndSettle();

    expect(saved.single.memberIds, ['l2tp']);
  });

  testWidgets('with every profile in the group there is nothing left to add', (
    tester,
  ) async {
    await pumpEditor(
      tester,
      group: const FailoverGroup(
        id: 'g1',
        name: 'Work',
        memberIds: ['l2tp', 'sstp', 'spare'],
      ),
    );

    final button = tester.widget<TextButton>(
      find.byKey(const Key('failover_group_add_member')),
    );
    expect(button.onPressed, isNull);
    expect(
      find.text('Every saved profile is already in this group.'),
      findsOneWidget,
    );
  });
}

Profile _profile(String id, String name, VpnProtocol protocol) => Profile(
  id: id,
  displayName: name,
  server: '$id.example.com',
  user: 'user',
  protocol: protocol,
);
