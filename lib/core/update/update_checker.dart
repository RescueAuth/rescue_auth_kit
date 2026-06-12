import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:package_info_plus/package_info_plus.dart';

enum UpdateCheckStatus {
  updateAvailable,
  upToDate,
  noReleaseFound,
  cannotCompare,
}

class UpdateCheckResult {
  const UpdateCheckResult({
    required this.status,
    required this.currentVersion,
    required this.currentBuildNumber,
    this.latestTag,
    this.releaseName,
    this.releaseUrl,
    this.releaseNotes,
  });

  final UpdateCheckStatus status;
  final String currentVersion;
  final String currentBuildNumber;
  final String? latestTag;
  final String? releaseName;
  final String? releaseUrl;
  final String? releaseNotes;

  bool get updateAvailable => status == UpdateCheckStatus.updateAvailable;
}

class UpdateCheckException implements Exception {
  const UpdateCheckException(this.message);

  final String message;

  @override
  String toString() => message;
}

class AppVersion implements Comparable<AppVersion> {
  const AppVersion._(this.parts, this.source);

  final List<int> parts;
  final String source;

  String get normalized => parts.join('.');

  static AppVersion? tryParse(String value) {
    final source = value.trim();
    if (source.isEmpty) return null;

    var core = source;
    if (core.startsWith('v') || core.startsWith('V')) {
      core = core.substring(1);
    }
    core = core.split('+').first.split('-').first;

    final parsed = <int>[];
    for (final rawPart in core.split('.')) {
      final match = RegExp(r'^(\d+)').firstMatch(rawPart.trim());
      if (match == null) return null;
      parsed.add(int.parse(match.group(1)!));
    }
    if (parsed.isEmpty) return null;
    while (parsed.length < 3) {
      parsed.add(0);
    }

    return AppVersion._(List.unmodifiable(parsed.take(3)), source);
  }

  @override
  int compareTo(AppVersion other) {
    for (var i = 0; i < parts.length; i += 1) {
      final left = parts[i];
      final right = other.parts[i];
      if (left != right) return left.compareTo(right);
    }
    return 0;
  }
}

typedef ReleaseFetcher = Future<GitHubRelease?> Function();

class UpdateChecker {
  const UpdateChecker({
    this.owner = 'xincy22',
    this.repo = 'rescue_auth_kit',
    this.timeout = const Duration(seconds: 10),
    this.endpoint,
    this.releaseFetcher,
  });

  final String owner;
  final String repo;
  final Duration timeout;
  final Uri? endpoint;
  final ReleaseFetcher? releaseFetcher;

  Uri get latestReleaseUri =>
      endpoint ??
      Uri.https('api.github.com', '/repos/$owner/$repo/releases/latest');

  Future<UpdateCheckResult> check() async {
    final packageInfo = await PackageInfo.fromPlatform();
    return checkForVersion(
      currentVersion: packageInfo.version,
      currentBuildNumber: packageInfo.buildNumber,
    );
  }

  Future<UpdateCheckResult> checkForVersion({
    required String currentVersion,
    String currentBuildNumber = '',
  }) async {
    final release = await (releaseFetcher ?? _fetchLatestRelease)();
    if (release == null) {
      return UpdateCheckResult(
        status: UpdateCheckStatus.noReleaseFound,
        currentVersion: currentVersion,
        currentBuildNumber: currentBuildNumber,
      );
    }

    final current = AppVersion.tryParse(currentVersion);
    final latest = AppVersion.tryParse(release.tagName);
    if (current == null || latest == null) {
      return UpdateCheckResult(
        status: UpdateCheckStatus.cannotCompare,
        currentVersion: currentVersion,
        currentBuildNumber: currentBuildNumber,
        latestTag: release.tagName,
        releaseName: release.name,
        releaseUrl: release.htmlUrl,
        releaseNotes: release.body,
      );
    }

    return UpdateCheckResult(
      status: latest.compareTo(current) > 0
          ? UpdateCheckStatus.updateAvailable
          : UpdateCheckStatus.upToDate,
      currentVersion: currentVersion,
      currentBuildNumber: currentBuildNumber,
      latestTag: release.tagName,
      releaseName: release.name,
      releaseUrl: release.htmlUrl,
      releaseNotes: release.body,
    );
  }

  Future<GitHubRelease?> _fetchLatestRelease() async {
    final client = HttpClient()..connectionTimeout = timeout;
    try {
      final request = await client.getUrl(latestReleaseUri).timeout(timeout);
      request.headers.set(
        HttpHeaders.acceptHeader,
        'application/vnd.github+json',
      );
      request.headers.set(
        HttpHeaders.userAgentHeader,
        'RescueAuthKit update checker',
      );
      request.headers.set('X-GitHub-Api-Version', '2022-11-28');

      final response = await request.close().timeout(timeout);
      final body = await response
          .transform(utf8.decoder)
          .join()
          .timeout(timeout);

      if (response.statusCode == HttpStatus.notFound) return null;
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw UpdateCheckException(
          'GitHub returned HTTP ${response.statusCode}.',
        );
      }

      final decoded = jsonDecode(body);
      if (decoded is! Map<String, dynamic>) {
        throw const UpdateCheckException(
          'GitHub returned an unexpected response.',
        );
      }
      return GitHubRelease.fromJson(decoded);
    } on TimeoutException {
      throw const UpdateCheckException('GitHub update check timed out.');
    } on SocketException catch (e) {
      throw UpdateCheckException('Network error: ${e.message}');
    } on FormatException {
      throw const UpdateCheckException('GitHub returned invalid JSON.');
    } finally {
      client.close(force: true);
    }
  }
}

class GitHubRelease {
  const GitHubRelease({
    required this.tagName,
    required this.htmlUrl,
    this.name,
    this.body,
  });

  final String tagName;
  final String htmlUrl;
  final String? name;
  final String? body;

  factory GitHubRelease.fromJson(Map<String, dynamic> json) {
    final tagName = json['tag_name'];
    final htmlUrl = json['html_url'];
    if (tagName is! String || htmlUrl is! String) {
      throw const UpdateCheckException('GitHub release is missing tag or URL.');
    }

    return GitHubRelease(
      tagName: tagName,
      htmlUrl: htmlUrl,
      name: json['name'] is String ? json['name'] as String : null,
      body: json['body'] is String ? json['body'] as String : null,
    );
  }
}
