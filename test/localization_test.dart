import 'dart:convert';
import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tunnel_forge/l10n/app_localizations.dart';

void main() {
  test('both languages resolve, and Russian is not English', () {
    final en = lookupAppLocalizations(const Locale('en'));
    final ru = lookupAppLocalizations(const Locale('ru'));

    expect(en.save, 'Save');
    expect(ru.save, 'Сохранить');
    expect(
      ru.engineErrorAuthenticationFailed,
      isNot(en.engineErrorAuthenticationFailed),
    );
  });

  test('AppText follows the language the controller sets', () {
    AppText.setLanguage(AppLanguage.russian);
    expect(AppText.current.protocolLabel, 'Протокол');
    AppText.setLanguage(AppLanguage.english);
    expect(AppText.current.protocolLabel, 'Protocol');
  });

  test('a stored language code this build does not know falls back', () {
    expect(AppLanguage.fromCode('fa'), AppLanguage.english);
    expect(AppLanguage.fromCode('ru'), AppLanguage.russian);
    expect(AppLanguage.fromCode(null), AppLanguage.english);
  });

  test('Estedad is left to English; Russian keeps the platform font', () {
    // The bundled font carries no Cyrillic (SPEC 9.1.10).
    expect(AppLanguage.english.fontFamily, 'Estedad');
    expect(AppLanguage.russian.fontFamily, isNull);
  });

  test('update-check failures are phrased in the chosen language', () {
    final ru = lookupAppLocalizations(const Locale('ru'));

    expect(
      ru.updateCheckError('GitHub Releases request timed out.'),
      ru.updateCheckErrorTimeout,
    );
    expect(
      ru.updateCheckError('GitHub Releases returned HTTP 503.'),
      ru.updateCheckErrorHttp('503'),
    );
    // Anything this build has no sentence for is shown as it arrived.
    expect(ru.updateCheckError('something else'), 'something else');
  });

  test('every English key has a Russian one', () {
    Map<String, Object?> read(String path) =>
        jsonDecode(File(path).readAsStringSync()) as Map<String, Object?>;

    final en = read('lib/l10n/app_en.arb');
    final ru = read('lib/l10n/app_ru.arb');
    final enKeys = en.keys.where((key) => !key.startsWith('@')).toSet();
    final ruKeys = ru.keys.where((key) => !key.startsWith('@')).toSet();

    expect(enKeys.difference(ruKeys), isEmpty, reason: 'missing in app_ru.arb');
    expect(ruKeys.difference(enKeys), isEmpty, reason: 'stale in app_ru.arb');
    expect(enKeys, isNotEmpty);
  });
}
