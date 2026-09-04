// Failover groups below the widget layer (SPEC 10.1): the model, the store,
// the channel call, and the two blocs that decide what the button starts.
import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:tunnel_forge/core/vpn_protocol.dart';
import 'package:tunnel_forge/features/home/data/home_repositories_impl.dart';
import 'package:tunnel_forge/features/home/domain/home_repositories.dart';
import 'package:tunnel_forge/features/home/presentation/bloc/profiles_bloc.dart';
import 'package:tunnel_forge/features/profiles/data/profile_bridge.dart';
import 'package:tunnel_forge/features/profiles/data/profile_store.dart';
import 'package:tunnel_forge/features/profiles/domain/failover_group.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_transfer.dart';
import 'package:tunnel_forge/features/tunnel/data/vpn_client.dart';
import 'package:tunnel_forge/features/tunnel/data/vpn_contract.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('FailoverGroup', () {
    test('a budget outside the range this build runs falls back', () {
      expect(FailoverGroup.normalizeTimeout(15), 15);
      expect(FailoverGroup.normalizeTimeout(5), 5);
      expect(FailoverGroup.normalizeTimeout(120), 120);
      expect(
        FailoverGroup.normalizeTimeout(1),
        FailoverGroup.defaultConnectTimeoutSec,
      );
      expect(
        FailoverGroup.normalizeTimeout(900),
        FailoverGroup.defaultConnectTimeoutSec,
      );
    });

    test('a row without an id is not a group', () {
      expect(FailoverGroup.tryFromJson(null), isNull);
      expect(
        FailoverGroup.tryFromJson(<String, Object?>{'name': 'Work'}),
        isNull,
      );
      expect(FailoverGroup.tryFromJson(<String, Object?>{'id': '  '}), isNull);
    });

    test('reading a group keeps member order and drops repeats', () {
      final group = FailoverGroup.tryFromJson(<String, Object?>{
        'id': 'g1',
        'name': '  Work  ',
        'connectTimeoutSec': 4,
        'createdAt': 17,
        'memberIds': <Object?>['b', 'a', 'b', '', null, 'c'],
      });

      expect(group, isNotNull);
      expect(group!.name, 'Work');
      expect(group.memberIds, ['b', 'a', 'c']);
      // Out of range on the way in, so the host's clamp is not the only one.
      expect(group.connectTimeoutSec, FailoverGroup.defaultConnectTimeoutSec);
      expect(group.createdAt, 17);
    });

    test('members resolve in the group order, skipping ones that are gone', () {
      const group = FailoverGroup(
        id: 'g1',
        name: 'Work',
        memberIds: ['second', 'missing', 'first'],
      );
      final profiles = [_profile('first'), _profile('second')];

      expect(
        group
            .resolveMembers(profiles, (profile) => profile.id)
            .map((p) => p.id),
        ['second', 'first'],
      );
    });
  });

  group('the group store', () {
    test('drops members that do not name a saved profile', () async {
      final backend = MemoryProfileBackend();
      await _saveProfile(backend, 'kept');

      final stored = await backend.saveGroup(
        const FailoverGroup(
          id: '',
          name: 'Work',
          memberIds: ['kept', 'never-existed'],
        ),
      );

      expect(stored.memberIds, ['kept']);
      expect(stored.id, isNotEmpty);
      expect(stored.createdAt, greaterThan(0));
    });

    test('deleting a profile takes it out of every group naming it', () async {
      final backend = MemoryProfileBackend();
      await _saveProfile(backend, 'l2tp');
      await _saveProfile(backend, 'sstp');
      final stored = await backend.saveGroup(
        const FailoverGroup(id: '', name: 'Work', memberIds: ['l2tp', 'sstp']),
      );

      await backend.delete('l2tp');

      expect((await backend.loadGroup(stored.id))!.memberIds, ['sstp']);
    });

    test('an unnamed group is refused', () async {
      final backend = MemoryProfileBackend();
      await expectLater(
        backend.saveGroup(const FailoverGroup(id: '', name: '  ')),
        throwsA(
          isA<PlatformException>().having(
            (error) => error.code,
            'code',
            ProfileChannelContract.errorBadArgs,
          ),
        ),
      );
    });

    test(
      'the chosen group survives a restart and a deletion clears it',
      () async {
        SharedPreferences.setMockInitialValues(<String, Object>{});
        final backend = MemoryProfileBackend();
        await _saveProfile(backend, 'l2tp');
        final store = ProfileStore(
          prefsOverride: await SharedPreferences.getInstance(),
          secretsOverride: MemorySecretStore(),
          backendOverride: backend,
        );
        final stored = await store.saveFailoverGroup(
          const FailoverGroup(id: '', name: 'Work', memberIds: ['l2tp']),
        );

        await store.setLastGroupId(stored.id);
        expect(await store.loadLastGroupId(), stored.id);

        await store.deleteFailoverGroup(stored.id);
        expect(await store.loadLastGroupId(), isNull);
        expect(await store.loadFailoverGroups(), isEmpty);
      },
    );
  });

  group('ProfilesBloc', () {
    late MemoryProfileBackend backend;
    late ProfilesRepository repository;

    Future<ProfilesBloc> startedBloc() async {
      final bloc = ProfilesBloc(repository, _FakeTransferRepository());
      bloc.add(const ProfilesStarted());
      await bloc.stream.firstWhere((state) => !state.loading);
      return bloc;
    }

    setUp(() async {
      SharedPreferences.setMockInitialValues(<String, Object>{});
      backend = MemoryProfileBackend();
      repository = ProfilesRepositoryImpl(
        ProfileStore(
          prefsOverride: await SharedPreferences.getInstance(),
          secretsOverride: MemorySecretStore(),
          backendOverride: backend,
        ),
      );
      await _saveProfile(backend, 'l2tp');
      await _saveProfile(backend, 'sstp');
    });

    test(
      'a saved group appears in the list and is not selected by saving',
      () async {
        final bloc = await startedBloc();
        addTearDown(bloc.close);

        bloc.add(
          const ProfilesGroupSaveRequested(
            FailoverGroup(id: '', name: 'Work', memberIds: ['l2tp', 'sstp']),
          ),
        );
        final state = await bloc.stream.firstWhere(
          (state) => state.savedGroupId != null,
        );

        expect(state.groups, hasLength(1));
        expect(state.groups.single.memberIds, ['l2tp', 'sstp']);
        // Saving is not choosing: the connection the user has set up is not
        // replaced by the one they were just editing.
        expect(state.activeGroupId, isNull);
      },
    );

    test('an unnamed group is refused without reaching the store', () async {
      final bloc = await startedBloc();
      addTearDown(bloc.close);

      bloc.add(
        const ProfilesGroupSaveRequested(FailoverGroup(id: '', name: ' ')),
      );
      final state = await bloc.stream.firstWhere(
        (state) => state.message?.error ?? false,
      );

      expect(state.groups, isEmpty);
      expect(await backend.listGroups(), isEmpty);
    });

    test(
      'choosing a group drops the profile, and choosing a profile drops the group',
      () async {
        final bloc = await startedBloc();
        addTearDown(bloc.close);

        bloc.add(const ProfilesSelectionChanged('l2tp'));
        await bloc.stream.firstWhere(
          (state) => state.activeProfileId == 'l2tp',
        );

        bloc.add(
          const ProfilesGroupSaveRequested(
            FailoverGroup(id: '', name: 'Work', memberIds: ['l2tp', 'sstp']),
          ),
        );
        final saved = await bloc.stream.firstWhere(
          (state) => state.savedGroupId != null,
        );
        final groupId = saved.savedGroupId!;

        bloc.add(ProfilesGroupSelectionChanged(groupId));
        final withGroup = await bloc.stream.firstWhere(
          (state) => state.activeGroupId == groupId,
        );
        expect(withGroup.activeProfileId, isNull);
        expect(withGroup.activeProfileRow, isNull);
        expect(withGroup.hasActiveGroup, isTrue);
        expect(withGroup.activeGroupMembers.map((p) => p.id), ['l2tp', 'sstp']);

        bloc.add(const ProfilesSelectionChanged('sstp'));
        final withProfile = await bloc.stream.firstWhere(
          (state) => state.activeProfileId == 'sstp',
        );
        expect(withProfile.activeGroupId, isNull);
        expect(withProfile.hasActiveGroup, isFalse);
      },
    );

    test('a group left empty by profile deletions cannot be started', () async {
      final bloc = await startedBloc();
      addTearDown(bloc.close);

      bloc.add(
        const ProfilesGroupSaveRequested(
          FailoverGroup(id: '', name: 'Work', memberIds: ['l2tp']),
        ),
      );
      final saved = await bloc.stream.firstWhere(
        (state) => state.savedGroupId != null,
      );
      final groupId = saved.savedGroupId!;
      bloc.add(ProfilesGroupSelectionChanged(groupId));
      await bloc.stream.firstWhere((state) => state.activeGroupId == groupId);

      bloc.add(const ProfilesDeleteRequested('l2tp'));
      final state = await bloc.stream.firstWhere(
        (state) => !state.loading && !state.profiles.any((p) => p.id == 'l2tp'),
      );

      // Still chosen — it is still a group the user made — but not startable.
      expect(state.activeGroupId, groupId);
      expect(state.activeGroup, isNotNull);
      expect(state.hasActiveGroup, isFalse);
    });

    test('deleting the chosen group leaves nothing chosen', () async {
      final bloc = await startedBloc();
      addTearDown(bloc.close);

      bloc.add(
        const ProfilesGroupSaveRequested(
          FailoverGroup(id: '', name: 'Work', memberIds: ['l2tp']),
        ),
      );
      final saved = await bloc.stream.firstWhere(
        (state) => state.savedGroupId != null,
      );
      final groupId = saved.savedGroupId!;
      bloc.add(ProfilesGroupSelectionChanged(groupId));
      await bloc.stream.firstWhere((state) => state.activeGroupId == groupId);

      bloc.add(ProfilesGroupDeleteRequested(groupId));
      final state = await bloc.stream.firstWhere(
        (state) => state.groups.isEmpty,
      );

      expect(state.activeGroupId, isNull);
      expect(state.hasActiveGroup, isFalse);
    });

    test('the chosen group is picked back up on the next start', () async {
      final first = await startedBloc();
      first.add(
        const ProfilesGroupSaveRequested(
          FailoverGroup(id: '', name: 'Work', memberIds: ['l2tp', 'sstp']),
        ),
      );
      final saved = await first.stream.firstWhere(
        (state) => state.savedGroupId != null,
      );
      final groupId = saved.savedGroupId!;
      first.add(ProfilesGroupSelectionChanged(groupId));
      await first.stream.firstWhere((state) => state.activeGroupId == groupId);
      await first.close();

      final second = await startedBloc();
      addTearDown(second.close);

      expect(second.state.activeGroupId, groupId);
      expect(second.state.activeProfileId, isNull);
    });
  });

  group('VpnClient.connectGroup', () {
    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel(VpnContract.channel),
            null,
          );
    });

    test(
      'sends the group id and the proxy settings, and nothing else',
      () async {
        MethodCall? seen;
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(
              const MethodChannel(VpnContract.channel),
              (call) async {
                seen = call;
                return null;
              },
            );

        await VpnClient().connectGroup(
          groupId: 'g1',
          attemptId: 'attempt-1',
          proxySettings: const ProxySettings(
            httpPort: 8081,
            socksPort: 1081,
            allowLanConnections: true,
          ),
        );

        expect(seen!.method, VpnContract.connectGroup);
        final args = (seen!.arguments as Map).cast<String, Object?>();
        expect(args[VpnContract.argGroupId], 'g1');
        expect(args[VpnContract.argAttemptId], 'attempt-1');
        expect(args[VpnContract.argProxyHttpPort], 8081);
        expect(args[VpnContract.argProxySocksPort], 1081);
        expect(args[VpnContract.argProxyAllowLan], isTrue);
        // No server, credentials or split-tunnel choice: the members carry their
        // own, and the host reads them from the store (SPEC 10.1).
        expect(args.containsKey(VpnContract.argServer), isFalse);
        expect(args.containsKey(VpnContract.argPassword), isFalse);
      },
    );
  });
}

Profile _profile(String id) => Profile(
  id: id,
  displayName: id,
  server: '$id.example.com',
  user: 'user',
  protocol: VpnProtocol.l2tp,
);

Future<void> _saveProfile(MemoryProfileBackend backend, String id) {
  return backend.save(_profile(id), password: 'pw', psk: 'psk');
}

class _FakeTransferRepository implements ProfileTransferRepository {
  @override
  Stream<IncomingProfileTransfer> get incomingTransfers => const Stream.empty();

  @override
  Future<void> dispose() async {}

  @override
  Future<List<IncomingProfileTransfer>> start() async =>
      const <IncomingProfileTransfer>[];
}
