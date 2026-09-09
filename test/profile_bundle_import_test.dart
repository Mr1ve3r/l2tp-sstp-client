import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tunnel_forge/core/vpn_protocol.dart';
import 'package:tunnel_forge/features/home/data/home_repositories_impl.dart';
import 'package:tunnel_forge/features/profiles/data/profile_bridge.dart';
import 'package:tunnel_forge/features/profiles/data/profile_store.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_bundle.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_transfer.dart';

import 'support/fake_certificates_repository.dart';

Profile _profile({
  String id = 'local',
  String name = 'Amsterdam',
  String server = 'ams.acme.example',
  String user = '',
  VpnProtocol protocol = VpnProtocol.l2tp,
  int port = 1701,
}) {
  return Profile(
    id: id,
    displayName: name,
    server: server,
    user: user,
    protocol: protocol,
    port: port,
    dnsAutomatic: true,
    dns1Host: '',
    dns1Protocol: DnsProtocol.dnsOverUdp,
    dns2Host: '',
    dns2Protocol: DnsProtocol.dnsOverUdp,
  );
}

/// One entry as an administrator's set carries it: shared secrets, no login.
ProfileTransferEnvelope _entry({String name = 'Amsterdam', int mtu = 1400}) {
  return ProfileTransferEnvelope(
    profile: _profile(name: name).copyWith(mtu: mtu),
    psk: 'corporate-psk',
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('matchingProfileFor', () {
    test('matches on protocol, server, port and name, ignoring case', () {
      final existing = [
        _profile(id: 'a', server: 'AMS.acme.example', name: 'amsterdam'),
      ];

      expect(matchingProfileFor(_entry(), existing)?.id, 'a');
    });

    test('a different protocol or port is a different profile', () {
      expect(
        matchingProfileFor(_entry(), [
          _profile(id: 'a', protocol: VpnProtocol.sstp, port: 443),
        ]),
        isNull,
      );
      expect(
        matchingProfileFor(_entry(), [_profile(id: 'a', port: 1702)]),
        isNull,
      );
    });

    /// The login is the field the set leaves empty and the recipient fills in,
    /// so it cannot take part in deciding whether two profiles are the same.
    test('the login does not affect the match', () {
      expect(
        matchingProfileFor(_entry(), [_profile(id: 'a', user: 'alice')])?.id,
        'a',
      );
    });
  });

  group('importProfileBundle', () {
    late ProfileStore store;
    late ProfilesRepositoryImpl repository;

    setUp(() async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();
      store = ProfileStore(
        prefsOverride: prefs,
        secretsOverride: MemorySecretStore(),
        backendOverride: MemoryProfileBackend(),
      );
      repository = ProfilesRepositoryImpl(store, FakeCertificatesRepository());
    });

    test(
      'adds every chosen entry and marks it as awaiting credentials',
      () async {
        final bundle = ProfileBundle(
          name: 'Acme',
          entries: [
            _entry(name: 'Amsterdam'),
            _entry(name: 'Berlin'),
          ],
        );

        final result = await repository.importProfileBundle(
          bundle: bundle,
          choices: const [
            BundleImportChoice(entryIndex: 0, action: BundleImportAction.add),
            BundleImportChoice(entryIndex: 1, action: BundleImportAction.add),
          ],
        );

        expect(result.added, 2);
        expect(result.stored, 2);
        final profiles = await store.loadProfiles();
        expect(profiles, hasLength(2));
        expect(
          await store.loadProfilesAwaitingCredentials(),
          profiles.map((profile) => profile.id).toSet(),
        );
        final row = await store.loadProfileWithSecrets(profiles.first.id);
        expect(row!.psk, 'corporate-psk');
        expect(row.password, isEmpty);
      },
    );

    test('skip stores nothing', () async {
      final result = await repository.importProfileBundle(
        bundle: ProfileBundle(name: 'Acme', entries: [_entry()]),
        choices: const [
          BundleImportChoice(entryIndex: 0, action: BundleImportAction.skip),
        ],
      );

      expect(result.skipped, 1);
      expect(result.stored, 0);
      expect(await store.loadProfiles(), isEmpty);
    });

    /// The whole point of offering "replace": a new handout from the
    /// organisation must not sign the recipient out of the last one.
    test('replace keeps the login and password already entered', () async {
      await store.upsertProfile(
        _profile(id: 'existing', user: 'alice'),
        password: 'alice-password',
        psk: 'old-psk',
      );

      final result = await repository.importProfileBundle(
        bundle: ProfileBundle(name: 'Acme', entries: [_entry(mtu: 1380)]),
        choices: const [
          BundleImportChoice(
            entryIndex: 0,
            action: BundleImportAction.replace,
            targetProfileId: 'existing',
          ),
        ],
      );

      expect(result.replaced, 1);
      final profiles = await store.loadProfiles();
      expect(profiles, hasLength(1));
      expect(profiles.single.id, 'existing');
      expect(profiles.single.user, 'alice');
      expect(profiles.single.mtu, 1380);
      final row = await store.loadProfileWithSecrets('existing');
      expect(row!.password, 'alice-password');
      expect(row.psk, 'corporate-psk');
      // It has its credentials, so it is not waiting for any.
      expect(await store.loadProfilesAwaitingCredentials(), isEmpty);
    });

    test('replace onto a profile that has gone stores a new one', () async {
      final result = await repository.importProfileBundle(
        bundle: ProfileBundle(name: 'Acme', entries: [_entry()]),
        choices: const [
          BundleImportChoice(
            entryIndex: 0,
            action: BundleImportAction.replace,
            targetProfileId: 'deleted-since-the-sheet-opened',
          ),
        ],
      );

      expect(result.added, 1);
      expect(result.replaced, 0);
      expect(await store.loadProfiles(), hasLength(1));
    });

    /// The set carries the order its gateways are tried in; the recipient
    /// should not have to rebuild it by hand after every handout.
    test('builds the failover group of the set out of what landed', () async {
      final result = await repository.importProfileBundle(
        bundle: ProfileBundle(
          name: 'Acme',
          entries: [
            _entry(name: 'Amsterdam'),
            _entry(name: 'Berlin'),
          ],
          groups: const [
            BundledFailoverGroup(
              name: 'Acme',
              connectTimeoutSec: 25,
              memberIndexes: [1, 0],
            ),
          ],
        ),
        choices: const [
          BundleImportChoice(entryIndex: 0, action: BundleImportAction.add),
          BundleImportChoice(entryIndex: 1, action: BundleImportAction.add),
        ],
      );

      expect(result.groupsAdded, 1);
      final groups = await store.loadFailoverGroups();
      expect(groups, hasLength(1));
      expect(groups.single.name, 'Acme');
      expect(groups.single.connectTimeoutSec, 25);
      final profiles = await store.loadProfiles();
      final byName = <String, String>{
        for (final profile in profiles) profile.displayName: profile.id,
      };
      // Berlin first, because that is the order the set put them in — not the
      // order they happened to be stored.
      expect(groups.single.memberIds, [byName['Berlin'], byName['Amsterdam']]);
    });

    test(
      'a skipped member is left out rather than blocking the group',
      () async {
        final result = await repository.importProfileBundle(
          bundle: ProfileBundle(
            name: 'Acme',
            entries: [
              _entry(name: 'Amsterdam'),
              _entry(name: 'Berlin'),
            ],
            groups: const [
              BundledFailoverGroup(name: 'Acme', memberIndexes: [0, 1]),
            ],
          ),
          choices: const [
            BundleImportChoice(entryIndex: 0, action: BundleImportAction.add),
            BundleImportChoice(entryIndex: 1, action: BundleImportAction.skip),
          ],
        );

        expect(result.groupsAdded, 1);
        final groups = await store.loadFailoverGroups();
        expect(groups.single.memberIds, hasLength(1));
      },
    );

    test('a group with nothing left is not created at all', () async {
      final result = await repository.importProfileBundle(
        bundle: ProfileBundle(
          name: 'Acme',
          entries: [_entry(name: 'Amsterdam')],
          groups: const [
            BundledFailoverGroup(name: 'Acme', memberIndexes: [0]),
          ],
        ),
        choices: const [
          BundleImportChoice(entryIndex: 0, action: BundleImportAction.skip),
        ],
      );

      expect(result.groupsAdded, 0);
      expect(await store.loadFailoverGroups(), isEmpty);
    });

    /// The same set is handed out again whenever the organisation changes
    /// something, and the third handout should not leave three groups.
    test('importing the same set twice rewrites its group', () async {
      ProfileBundle bundle() => ProfileBundle(
        name: 'Acme',
        entries: [
          _entry(name: 'Amsterdam'),
          _entry(name: 'Berlin'),
        ],
        groups: const [
          BundledFailoverGroup(
            name: 'Acme',
            connectTimeoutSec: 25,
            memberIndexes: [0, 1],
          ),
        ],
      );
      const choices = [
        BundleImportChoice(entryIndex: 0, action: BundleImportAction.add),
        BundleImportChoice(entryIndex: 1, action: BundleImportAction.add),
      ];

      final first = await repository.importProfileBundle(
        bundle: bundle(),
        choices: choices,
      );
      final profiles = await store.loadProfiles();
      final second = await repository.importProfileBundle(
        bundle: bundle(),
        choices: [
          for (final profile in profiles)
            BundleImportChoice(
              entryIndex: profile.displayName == 'Amsterdam' ? 0 : 1,
              action: BundleImportAction.replace,
              targetProfileId: profile.id,
            ),
        ],
      );

      expect(first.groupsAdded, 1);
      expect(second.groupsAdded, 0);
      expect(second.groupsUpdated, 1);
      expect(await store.loadFailoverGroups(), hasLength(1));
    });

    test('deleting a profile stops it being marked as awaiting', () async {
      final result = await repository.importProfileBundle(
        bundle: ProfileBundle(name: 'Acme', entries: [_entry()]),
        choices: const [
          BundleImportChoice(entryIndex: 0, action: BundleImportAction.add),
        ],
      );

      await store.deleteProfile(result.firstImportedId!);

      expect(await store.loadProfilesAwaitingCredentials(), isEmpty);
    });
  });
}
