# Shortcuts for the checks we run most often. Keep the actual build logic in
# Flutter, Gradle, and CMake.

ANDROID_DIR := android
GRADLEW := ./gradlew

.PHONY: help
help:
	@printf '%s\n' \
		'Common targets:' \
		'  make format          Format Dart and Android native C sources' \
		'  make check           Run analysis, Android lint, ktlint, and native C checks' \
		'  make test            Run Flutter, Android, module, and native C tests' \
		'  make build-debug     Build the Android debug APK' \
		'' \
		'Focused targets:' \
		'  make flutter-format  Format Dart sources' \
		'  make flutter-analyze Run Flutter analyzer' \
		'  make flutter-test    Run Flutter tests' \
		'  make android-lint    Run Android lint for debug' \
		'  make android-test    Run Android debug unit tests' \
		'  make ktlint          Check Kotlin style in the engine and core modules' \
		'  make ktlint-format   Reformat Kotlin sources in those modules' \
		'  make module-test     Run unit tests for the engine and core modules' \
		'  make native-format   Format Android native C sources' \
		'  make native-check    Run native C format, lint, and unit-test checks' \
		'  make native-test     Run native C unit tests'

.PHONY: format
format: flutter-format ktlint-format native-format

.PHONY: check
check: flutter-analyze android-lint ktlint native-check

.PHONY: test
test: flutter-test android-test module-test native-test

.PHONY: build-debug
build-debug:
	cd $(ANDROID_DIR) && $(GRADLEW) :app:assembleDebug

.PHONY: flutter-format
flutter-format:
	dart format lib test

.PHONY: flutter-analyze
flutter-analyze:
	flutter analyze

.PHONY: flutter-test
flutter-test:
	flutter test

.PHONY: android-lint
android-lint:
	cd $(ANDROID_DIR) && $(GRADLEW) :app:lintDebug

.PHONY: android-test
android-test:
	cd $(ANDROID_DIR) && $(GRADLEW) --stacktrace :app:testDebugUnitTest

.PHONY: native-format
native-format:
	cd $(ANDROID_DIR) && $(GRADLEW) :app:formatAndroidNativeC

.PHONY: native-check
native-check:
	cd $(ANDROID_DIR) && $(GRADLEW) :app:checkNativeC

.PHONY: native-test
native-test:
	cd $(ANDROID_DIR) && $(GRADLEW) :app:testNativeC

# Style and tests for the modules this fork adds. `:app` keeps upstream
# formatting: ktlint is not applied there (see the module build files).
MODULES := engine-api engine-l2tp engine-sstp core-tunnel core-trust
MODULE_TEST_TASKS := $(foreach m,$(MODULES),:$(m):testDebugUnitTest)

.PHONY: ktlint
ktlint:
	cd $(ANDROID_DIR) && $(GRADLEW) ktlintCheck

.PHONY: ktlint-format
ktlint-format:
	cd $(ANDROID_DIR) && $(GRADLEW) ktlintFormat

.PHONY: module-test
module-test:
	cd $(ANDROID_DIR) && $(GRADLEW) --stacktrace $(MODULE_TEST_TASKS)
