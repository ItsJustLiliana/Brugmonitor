<?php

declare(strict_types=1);

require_once __DIR__ . '/../includes/db.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

function brugmonitor_release_respond(array $payload, int $statusCode = 200): never
{
    http_response_code($statusCode);
    echo json_encode($payload, JSON_UNESCAPED_SLASHES | JSON_INVALID_UTF8_SUBSTITUTE);
    exit;
}

function brugmonitor_release_base_url(): string
{
    $forwardedProto = strtolower(trim(explode(',', (string) ($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? ''))[0]));
    $isHttps = (!empty($_SERVER['HTTPS']) && strtolower((string) $_SERVER['HTTPS']) !== 'off')
        || (string) ($_SERVER['SERVER_PORT'] ?? '') === '443'
        || $forwardedProto === 'https';
    $host = trim((string) ($_SERVER['HTTP_HOST'] ?? $_SERVER['SERVER_NAME'] ?? ''));
    if ($host === '' || preg_match('/[\r\n]/', $host)) {
        throw new RuntimeException('Public host is unavailable.');
    }

    $scriptPath = str_replace('\\', '/', (string) ($_SERVER['SCRIPT_NAME'] ?? '/api/brugmonitor-release.php'));
    $basePath = rtrim(str_replace('\\', '/', dirname(dirname($scriptPath))), '/');
    return ($isHttps ? 'https' : 'http') . '://' . $host . $basePath;
}

try {
    $pdo = db();
    $projects = $pdo->query(
        'SELECT id, name FROM website_projects WHERE is_public = 1 ORDER BY sort_order ASC, id ASC'
    );

    $projectId = 0;
    while ($project = $projects->fetch()) {
        $normalizedName = preg_replace('/[^a-z0-9]+/', '', strtolower((string) ($project['name'] ?? ''))) ?? '';
        if ($normalizedName === 'brugmonitor') {
            $projectId = (int) $project['id'];
            break;
        }
    }

    if ($projectId <= 0) {
        brugmonitor_release_respond(['data' => null]);
    }

    $statement = $pdo->prepare(
        'SELECT id, version, released_at, file_size_bytes, notes, changelog_markdown,
                file_sha256, apk_package, apk_version_name, apk_version_code, updated_at
         FROM website_project_versions
         WHERE project_id = :project_id
           AND is_archived = 0
           AND show_coming_soon = 0
           AND download_url <> \'\'
         ORDER BY apk_version_code DESC, sort_order DESC, id DESC
         LIMIT 1'
    );
    $statement->execute(['project_id' => $projectId]);
    $release = $statement->fetch();
    if (!is_array($release)) {
        brugmonitor_release_respond(['data' => null]);
    }

    $sha256 = strtolower(trim((string) ($release['file_sha256'] ?? '')));
    $buildNumber = (int) ($release['apk_version_code'] ?? 0);
    $packageName = trim((string) ($release['apk_package'] ?? ''));
    if ($buildNumber <= 0 || !preg_match('/^[a-f0-9]{64}$/', $sha256) || $packageName !== 'nl.brugmonitor.app') {
        brugmonitor_release_respond([
            'data' => null,
            'error' => 'The newest Brugmonitor release has incomplete or invalid APK metadata.',
        ], 409);
    }

    $version = trim((string) ($release['apk_version_name'] ?: $release['version']));
    if (!preg_match('/^\d+\.\d+\.\d+$/', $version)) {
        brugmonitor_release_respond(['data' => null, 'error' => 'The APK version is invalid.'], 409);
    }

    $downloadUrl = brugmonitor_release_base_url() . '/api/download-project.php?version=' . (int) $release['id'];
    brugmonitor_release_respond([
        'data' => [
            'version' => $version,
            'buildNumber' => $buildNumber,
            'downloadUrl' => $downloadUrl,
            'sha256' => $sha256,
            'sizeBytes' => ($release['file_size_bytes'] ?? null) === null ? null : (int) $release['file_size_bytes'],
            'packageName' => $packageName,
            'notes' => trim((string) ($release['changelog_markdown'] ?: $release['notes'])),
            'releasedAt' => (string) ($release['released_at'] ?: $release['updated_at']),
        ],
    ]);
} catch (Throwable $error) {
    error_log('Brugmonitor release feed failed: ' . $error->getMessage());
    brugmonitor_release_respond(['data' => null, 'error' => 'Could not load the Brugmonitor release.'], 500);
}
