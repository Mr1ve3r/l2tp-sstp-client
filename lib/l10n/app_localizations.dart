// The application's view of localisation.
//
// The strings themselves live in `app_en.arb` and `app_ru.arb` and are compiled
// into `generated/app_localizations.dart` by `flutter gen-l10n` (SPEC 9.1.10).
// This file re-exports that class, so every `import 'l10n/app_localizations.dart'`
// keeps resolving, and adds the two things the generated code cannot provide:
// the language the user picked, and a context-free way to read a string from a
// bloc.
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:tunnel_forge/l10n/generated/app_localizations.dart';

export 'package:tunnel_forge/l10n/generated/app_localizations.dart';

enum AppLanguage {
  english('en', 'English', 'English'),
  russian('ru', 'Russian', 'Русский');

  const AppLanguage(this.code, this.englishName, this.nativeName);

  final String code;
  final String englishName;
  final String nativeName;

  Locale get locale => Locale(code);

  /// The font the interface uses for this language, or null for the platform
  /// default. Estedad carries no Cyrillic, so Russian does not use it.
  String? get fontFamily => this == AppLanguage.russian ? null : 'Estedad';

  static AppLanguage fromCode(String? code) {
    return switch (code) {
      'ru' => AppLanguage.russian,
      _ => AppLanguage.english,
    };
  }
}

class AppLanguageController extends ChangeNotifier {
  AppLanguageController() {
    _load();
  }

  static const String _prefsKey = 'app_language_v1';

  AppLanguage _language = AppLanguage.english;

  AppLanguage get language => _language;

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    // A build before this one could have stored 'fa'; fromCode falls back to
    // English for anything it does not know.
    final next = AppLanguage.fromCode(prefs.getString(_prefsKey));
    if (next == _language) return;
    _language = next;
    AppText.setLanguage(next);
    notifyListeners();
  }

  Future<void> setLanguage(AppLanguage language) async {
    if (language == _language) return;
    _language = language;
    AppText.setLanguage(language);
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_prefsKey, language.code);
  }

  static AppLanguageController of(BuildContext context) {
    final scope = context
        .dependOnInheritedWidgetOfExactType<AppLanguageScope>();
    assert(scope != null, 'No AppLanguageScope found in context.');
    return scope!.controller;
  }
}

class AppLanguageScope extends InheritedNotifier<AppLanguageController> {
  const AppLanguageScope({
    super.key,
    required AppLanguageController controller,
    required super.child,
  }) : super(notifier: controller);

  AppLanguageController get controller => notifier!;
}

/// The strings, without a [BuildContext].
///
/// Blocs report failures and some text is built off the widget tree, so both
/// need the current language without a context to look it up with.
/// [AppLanguageController] is the one thing that changes it.
class AppText {
  AppText._();

  static AppLocalizations current = lookupAppLocalizations(const Locale('en'));

  static void setLanguage(AppLanguage language) {
    current = lookupAppLocalizations(language.locale);
  }
}

/// The update check reports failures as English sentences from the repository
/// layer; this turns the ones it knows into the user's language.
extension AppUpdateText on AppLocalizations {
  String updateCheckError(String message) {
    const httpPrefix = 'GitHub Releases returned HTTP ';
    if (message.startsWith(httpPrefix)) {
      return updateCheckErrorHttp(
        message.replaceFirst(httpPrefix, '').replaceFirst('.', ''),
      );
    }
    return switch (message) {
      'Network error while contacting GitHub Releases.' =>
        updateCheckErrorNetwork,
      'GitHub Releases request timed out.' => updateCheckErrorTimeout,
      'Secure connection to GitHub Releases failed.' => updateCheckErrorTls,
      'GitHub Releases returned malformed data.' => updateCheckErrorMalformed,
      'GitHub Releases returned no usable releases.' =>
        updateCheckErrorNoReleases,
      'Unexpected error while checking GitHub Releases.' =>
        updateCheckErrorUnexpected,
      'Installed version unavailable, so this build cannot be compared.' =>
        installedVersionUnavailableCompare,
      _ => message,
    };
  }
}
