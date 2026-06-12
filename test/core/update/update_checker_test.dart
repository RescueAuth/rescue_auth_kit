import 'package:flutter_test/flutter_test.dart';
import 'package:rescue_auth_kit/core/update/update_checker.dart';

void main() {
  group('AppVersion', () {
    test('parses common GitHub release tags', () {
      expect(AppVersion.tryParse('v1.2.3')?.normalized, '1.2.3');
      expect(AppVersion.tryParse('1.2')?.normalized, '1.2.0');
      expect(AppVersion.tryParse('1.2.3+4')?.normalized, '1.2.3');
      expect(AppVersion.tryParse('v1.2.3-beta.1')?.normalized, '1.2.3');
    });

    test('compares semantic versions', () {
      final current = AppVersion.tryParse('1.0.0')!;

      expect(AppVersion.tryParse('1.0.1')!.compareTo(current), greaterThan(0));
      expect(AppVersion.tryParse('1.1.0')!.compareTo(current), greaterThan(0));
      expect(AppVersion.tryParse('2.0.0')!.compareTo(current), greaterThan(0));
      expect(AppVersion.tryParse('1.0.0+9')!.compareTo(current), 0);
      expect(AppVersion.tryParse('0.9.9')!.compareTo(current), lessThan(0));
    });

    test('rejects tags without a numeric version', () {
      expect(AppVersion.tryParse('latest'), isNull);
      expect(AppVersion.tryParse(''), isNull);
    });
  });

  group('UpdateChecker', () {
    test('copies GitHub release body into releaseNotes', () async {
      final checker = UpdateChecker(
        releaseFetcher: () async => const GitHubRelease(
          tagName: 'v1.3.0',
          htmlUrl: 'https://example.com/release',
          name: 'RescueAuthKit 1.3.0',
          body: 'Release note body',
        ),
      );

      final result = await checker.checkForVersion(currentVersion: '1.2.0');

      expect(result.status, UpdateCheckStatus.updateAvailable);
      expect(result.releaseNotes, 'Release note body');
    });

    test('parses release body from GitHub JSON', () {
      final release = GitHubRelease.fromJson(const {
        'tag_name': 'v1.3.0',
        'html_url': 'https://example.com/release',
        'name': 'Release name',
        'body': 'Body text',
      });

      expect(release.body, 'Body text');
    });
  });
}
