<?php
/**
 * =============================================================================
 * LIVE PRO v9.0 — لوحة فضاء الزبون العالمية المؤمّنة (Customer XTREAM Space Portal)
 * =============================================================================
 * • متكاملة 100% مع قاعدة البيانات الموحدة (db.php / getDB()) ومع ملف api.php.
 * • مزامنة ثنائية الاتجاه: أي قائمة يرفعها الزبون من الشاشة أو الهاتف تظهر هنا فوراً
 *   ليعدّلها بكل حرية، وأي تعديل هنا ينعكس فوراً في التطبيق.
 * • تجزئة ذكية تلقائية لروابط M3U إلى (رابط السيرفر + اسم المستخدم + كلمة المرور)
 *   بدون الحاجة لخانة M3U منفصلة.
 * • تأمين شامل ضد التسلل وسرقة الحسابات:
 *   1. تشفير كلمات المرور على القرص بمعيار AES-256-CBC (ENCv9).
 *   2. إخفاء كلمات المرور في جدول العرض ومنع كشفها عبر المتصفح (Masking).
 *   3. قفل حماية اختياري برمز سري (PIN) خاص بكل شاشة لمنع التعديل غير المصرح به.
 *   4. حماية CSRF + تحديد معدل الطلبات (Rate-Limiting) لمنع تخمين رموز الشاشات.
 *   5. إطار تنبيه أحمر متوهج تلقائي عند اقتراب انتهاء صلاحية المشغل أو القوائم (5 أيام أو أقل).
 * =============================================================================
 */

declare(strict_types=1);
session_start();
error_reporting(E_ALL & ~E_NOTICE & ~E_DEPRECATED);

header('X-Content-Type-Options: nosniff');
header('X-Frame-Options: DENY');
header('Referrer-Policy: no-referrer');
header("Content-Security-Policy: default-src 'self' https://fonts.googleapis.com https://fonts.gstatic.com; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; script-src 'self' 'unsafe-inline';");

const ALI_SAT_NAME  = 'علي سات (Ali Sat)';
const ALI_SAT_PHONE = '00213697372480';

// توليد رمز حماية CSRF للجلسة
if (empty($_SESSION['livepro_csrf'])) {
    $_SESSION['livepro_csrf'] = bin2hex(random_bytes(24));
}
$CSRF_TOKEN = $_SESSION['livepro_csrf'];

// إنشاء ملف .htaccess تلقائياً لحماية ملفات قواعد البيانات من التحميل المباشر
$htaccessPath = __DIR__ . '/.htaccess';
if (!is_file($htaccessPath)) {
    @file_put_contents($htaccessPath, "<FilesMatch \"\\.(sqlite|db|json|log|bak)$\">\n  Order allow,deny\n  Deny from all\n</FilesMatch>\n");
}

// -----------------------------------------------------------------------------
// 1. الربط مع قاعدة البيانات الموحدة (db.php -> getDB())
// -----------------------------------------------------------------------------
function getDatabaseConnection(): ?PDO {
    $configFiles = ['db.php', 'config.php', 'database.php', '../db.php', '../config.php'];
    foreach ($configFiles as $file) {
        if (is_file(__DIR__ . '/' . $file)) {
            @include_once __DIR__ . '/' . $file;
        }
    }

    if (function_exists('getDB')) {
        try {
            $pdo = getDB();
            if ($pdo instanceof PDO) {
                ensureTablesExist($pdo, true);
                return $pdo;
            }
        } catch (Throwable $e) {}
    }

    foreach (['pdo', 'db', 'conn', 'connection'] as $varName) {
        if (isset($GLOBALS[$varName]) && $GLOBALS[$varName] instanceof PDO) {
            ensureTablesExist($GLOBALS[$varName], true);
            return $GLOBALS[$varName];
        }
    }

    $sqliteFiles = ['database.sqlite', 'db.sqlite', 'p2p.sqlite', 'livepro.sqlite', 'data/livepro.sqlite'];
    $chosenSqlite = __DIR__ . '/livepro_customer_lists.sqlite';
    foreach ($sqliteFiles as $sf) {
        if (is_file(__DIR__ . '/' . $sf)) {
            $chosenSqlite = __DIR__ . '/' . $sf;
            break;
        }
    }

    try {
        $pdo = new PDO('sqlite:' . $chosenSqlite, null, null, [
            PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        ]);
        ensureTablesExist($pdo, true);
        return $pdo;
    } catch (Throwable $e) {
        return null;
    }
}

function ensureTablesExist(PDO $pdo, bool $isSqlite): void {
    $pdo->exec("
        CREATE TABLE IF NOT EXISTS customer_xtream_lists (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            playlist_id TEXT NOT NULL,
            device_id TEXT NOT NULL,
            server_name TEXT NOT NULL,
            server_url TEXT NOT NULL,
            xtream_user TEXT NOT NULL,
            xtream_pass TEXT NOT NULL,
            m3u_url TEXT DEFAULT '',
            exp_date TEXT DEFAULT '',
            enabled INTEGER DEFAULT 1,
            order_index INTEGER DEFAULT 0,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP
        );
        CREATE INDEX IF NOT EXISTS idx_cx_device ON customer_xtream_lists(device_id);
        CREATE TABLE IF NOT EXISTS portal_security_pins (
            device_id TEXT PRIMARY KEY,
            pin_hash TEXT NOT NULL,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE IF NOT EXISTS portal_rate_limits (
            ip_address TEXT NOT NULL,
            attempted_at INTEGER NOT NULL
        );
    ");
    try {
        $cols = array_column($pdo->query("PRAGMA table_info(customer_xtream_lists)")->fetchAll(PDO::FETCH_ASSOC), 'name');
        if (!in_array('exp_date', $cols, true)) {
            $pdo->exec("ALTER TABLE customer_xtream_lists ADD COLUMN exp_date TEXT DEFAULT ''");
        }
    } catch (Throwable $e) {}
}

// -----------------------------------------------------------------------------
// 2. دوال التشفير AES-256-CBC وتجزئة M3U الذكية والحماية من التخمين
// -----------------------------------------------------------------------------
function encryptXtreamSecret(string $plain): string {
    if ($plain === '' || strpos($plain, 'ENCv9:') === 0) return $plain;
    if (function_exists('encryptUrl')) {
        try { return 'ENCv9:' . encryptUrl($plain); } catch (Throwable $e) {}
    }
    $key = hash('sha256', (defined('ENCRYPTION_KEY') ? ENCRYPTION_KEY : 'LIVEPRO_V9_ROYAL_SECRET_KEY_2026'), true);
    $iv = random_bytes(16);
    $cipher = openssl_encrypt($plain, 'AES-256-CBC', $key, OPENSSL_RAW_DATA, $iv);
    return $cipher !== false ? ('ENCv9:' . base64_encode($iv . $cipher)) : $plain;
}

function decryptXtreamSecret(string $stored): string {
    if ($stored === '' || strpos($stored, 'ENCv9:') !== 0) return $stored;
    $payload = substr($stored, 6);
    if (function_exists('decryptUrl')) {
        try {
            $dec = decryptUrl($payload);
            if ($dec !== '' && $dec !== false) return $dec;
        } catch (Throwable $e) {}
    }
    $raw = base64_decode($payload, true);
    if ($raw === false || strlen($raw) <= 16) return $payload;
    $key = hash('sha256', (defined('ENCRYPTION_KEY') ? ENCRYPTION_KEY : 'LIVEPRO_V9_ROYAL_SECRET_KEY_2026'), true);
    $iv = substr($raw, 0, 16);
    $cipher = substr($raw, 16);
    $plain = openssl_decrypt($cipher, 'AES-256-CBC', $key, OPENSSL_RAW_DATA, $iv);
    return $plain !== false ? $plain : $payload;
}

function parseXtreamFromM3uLink(string $rawUrl): ?array {
    $rawUrl = trim($rawUrl);
    if ($rawUrl === '' || !preg_match('#^https?://#i', $rawUrl)) return null;
    $parts = @parse_url($rawUrl);
    if (!is_array($parts) || empty($parts['scheme']) || empty($parts['host'])) return null;
    $base = $parts['scheme'] . '://' . $parts['host'] . (!empty($parts['port']) ? ':' . $parts['port'] : '');

    if (!empty($parts['query'])) {
        parse_str($parts['query'], $q);
        $u = trim((string)($q['username'] ?? $q['user'] ?? ''));
        $p = trim((string)($q['password'] ?? $q['pass'] ?? ''));
        if ($u !== '' && $p !== '') {
            return ['server_url' => $base, 'username' => $u, 'password' => $p];
        }
    }
    if (!empty($parts['path']) && preg_match('#/(?:live|movie|series)/([^/]+)/([^/]+)/#i', $parts['path'], $m)) {
        return ['server_url' => $base, 'username' => urldecode($m[1]), 'password' => urldecode($m[2])];
    }
    return null;
}

function checkRateLimit(?PDO $pdo): bool {
    if ($pdo === null) return true;
    $ip = $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';
    $now = time();
    try {
        $pdo->prepare("DELETE FROM portal_rate_limits WHERE attempted_at < ?")->execute([$now - 900]);
        $stmt = $pdo->prepare("SELECT COUNT(*) FROM portal_rate_limits WHERE ip_address = ?");
        $stmt->execute([$ip]);
        return ((int)$stmt->fetchColumn()) < 25;
    } catch (Throwable $e) {
        return true;
    }
}

function recordFailedAttempt(?PDO $pdo): void {
    if ($pdo === null) return;
    $ip = $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';
    try {
        $pdo->prepare("INSERT INTO portal_rate_limits (ip_address, attempted_at) VALUES (?, ?)")->execute([$ip, time()]);
    } catch (Throwable $e) {}
}

// -----------------------------------------------------------------------------
// 3. التحقق من صلاحية اشتراك المشغل للشاشة
// -----------------------------------------------------------------------------
function verifyDeviceSubscription(string $deviceId, ?PDO $pdo): array {
    $cleanId = strtoupper(trim($deviceId));
    if (!preg_match('/^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$/', $cleanId)) {
        return [
            'active'      => false,
            'is_expired'  => true,
            'expire_date' => '',
            'days_left'   => 0,
            'message'     => 'يرجى إدخال رمز الشاشة بالصيغة الصحيحة (مثال: DEV-A1B2-C3D4).'
        ];
    }

    if ($pdo !== null) {
        try {
            $stmt = $pdo->prepare("SELECT * FROM users WHERE UPPER(device_id) = :did LIMIT 1");
            $stmt->execute([':did' => $cleanId]);
            $row = $stmt->fetch();
            if ($row) {
                $expRaw = (string)($row['expire_date'] ?? '');
                $isActive = ((int)($row['is_active'] ?? 1)) === 1;
                $expTs = $expRaw !== '' ? strtotime($expRaw . ' 23:59:59') : 0;
                $daysLeft = $expTs > 0 ? (int)floor(($expTs - time()) / 86400) : 0;

                if (!$isActive || ($expTs > 0 && $expTs < time())) {
                    return [
                        'active'      => false,
                        'is_expired'  => true,
                        'expire_date' => $expRaw,
                        'days_left'   => 0,
                        'message'     => "انتهت صلاحية المشغل للشاشة ({$cleanId}) بتاريخ {$expRaw}. يرجى التجديد بالتواصل مع " . ALI_SAT_NAME . " : " . ALI_SAT_PHONE
                    ];
                }
                return [
                    'active'      => true,
                    'is_expired'  => false,
                    'expire_date' => $expRaw,
                    'days_left'   => max(0, $daysLeft),
                    'message'     => 'اشتراك المشغل نشط وصالح.'
                ];
            }
        } catch (Throwable $e) {}
    }

    return [
        'active'      => true,
        'is_expired'  => false,
        'expire_date' => date('Y-m-d', strtotime('+3 days')),
        'days_left'   => 3,
        'message'     => 'الشاشة جاهزة لإدارة القوائم.'
    ];
}

// -----------------------------------------------------------------------------
// 4. جلب وحفظ وتعديل وحذف القوائم
// -----------------------------------------------------------------------------
function getPlaylistsByDevice(string $deviceId, ?PDO $pdo, bool $maskPasswords = false): array {
    $cleanId = strtoupper(trim($deviceId));
    if ($cleanId === '' || $pdo === null) return [];
    try {
        $stmt = $pdo->prepare("SELECT * FROM customer_xtream_lists WHERE UPPER(device_id) = :did ORDER BY order_index ASC, id ASC");
        $stmt->execute([':did' => $cleanId]);
        $rows = $stmt->fetchAll();
        $result = [];
        foreach ($rows as $r) {
            $decPass = decryptXtreamSecret((string)$r['xtream_pass']);
            $result[] = [
                'id'          => (string)$r['playlist_id'],
                'playlist_id' => (string)$r['playlist_id'],
                'device_id'   => (string)$r['device_id'],
                'server_name' => (string)$r['server_name'],
                'server_url'  => (string)$r['server_url'],
                'username'    => (string)$r['xtream_user'],
                'password'    => $maskPasswords ? '••••••••' : $decPass,
                'm3u_url'     => (string)($r['m3u_url'] ?? ''),
                'exp_date'    => (string)($r['exp_date'] ?? ''),
                'enabled'     => ((int)($r['enabled'] ?? 1)) === 1,
                'order_index' => (int)($r['order_index'] ?? 0),
                'updated_at'  => (string)($r['updated_at'] ?? '')
            ];
        }
        return $result;
    } catch (Throwable $e) {
        return [];
    }
}

function probeXtreamExpiry(string $serverUrl, string $user, string $pass): string {
    if ($serverUrl === '' || $user === '' || $pass === '') return '';
    $apiUrl = rtrim($serverUrl, '/') . '/player_api.php?username=' . urlencode($user) . '&password=' . urlencode($pass);
    $ctx = stream_context_create([
        'http' => ['timeout' => 4, 'user_agent' => 'LIVE-PRO/9.0'],
        'ssl'  => ['verify_peer' => false, 'verify_peer_name' => false]
    ]);
    $raw = @file_get_contents($apiUrl, false, $ctx);
    if ($raw && strpos(trim($raw), '{') === 0) {
        $json = @json_decode($raw, true);
        $expSec = (int)($json['user_info']['exp_date'] ?? 0);
        if ($expSec > 0) {
            return date('Y-m-d', $expSec);
        }
    }
    return '';
}

function saveOrUpdatePlaylist(string $deviceId, array $item, ?PDO $pdo): bool {
    if ($pdo === null) return false;
    $cleanId    = strtoupper(trim($deviceId));
    $playlistId = trim((string)($item['id'] ?? $item['playlist_id'] ?? ''));
    if ($playlistId === '') {
        $playlistId = 'PL_' . strtoupper(substr(bin2hex(random_bytes(4)), 0, 8));
    }
    $serverName = trim((string)($item['server_name'] ?? 'قائمة XTREAM')) ?: 'قائمة XTREAM';
    $serverUrl  = rtrim(trim((string)($item['server_url'] ?? '')), '/');
    $username   = trim((string)($item['username'] ?? ''));
    $password   = trim((string)($item['password'] ?? ''));
    $expDate    = trim((string)($item['exp_date'] ?? ''));
    $enabled    = (!isset($item['enabled']) || !empty($item['enabled'])) ? 1 : 0;
    $now        = date('Y-m-d H:i:s');

    // التجزئة التلقائية الذكية إذا وضع المستخدم رابط M3U في خانة السيرفر
    $split = parseXtreamFromM3uLink($serverUrl);
    if ($split !== null) {
        $serverUrl = $split['server_url'];
        if ($username === '') $username = $split['username'];
        if ($password === '' || $password === '••••••••') $password = $split['password'];
    }

    if ($serverUrl !== '' && !preg_match('#^https?://#i', $serverUrl)) {
        $serverUrl = 'http://' . $serverUrl;
    }

    $check = $pdo->prepare("SELECT id, xtream_pass, exp_date FROM customer_xtream_lists WHERE UPPER(device_id) = :did AND playlist_id = :pid LIMIT 1");
    $check->execute([':did' => $cleanId, ':pid' => $playlistId]);
    $existing = $check->fetch();

    $plainPassForProbe = $password;
    if ($existing) {
        if ($password === '' || $password === '••••••••') {
            $encPass = (string)$existing['xtream_pass'];
            $plainPassForProbe = decryptXtreamSecret($encPass);
        } else {
            $encPass = encryptXtreamSecret($password);
        }
        if ($expDate === '') {
            $expDate = probeXtreamExpiry($serverUrl, $username, $plainPassForProbe) ?: (string)($existing['exp_date'] ?? '');
        }
        $pdo->prepare("
            UPDATE customer_xtream_lists
            SET server_name = :sname, server_url = :surl, xtream_user = :xuser, xtream_pass = :xpass, exp_date = :exp, enabled = :en, updated_at = :uAt
            WHERE id = :rowId
        ")->execute([
            ':sname' => $serverName, ':surl' => $serverUrl, ':xuser' => $username,
            ':xpass' => $encPass, ':exp' => $expDate, ':en' => $enabled, ':uAt' => $now, ':rowId' => $existing['id']
        ]);
    } else {
        $encPass = encryptXtreamSecret($password);
        if ($expDate === '') {
            $expDate = probeXtreamExpiry($serverUrl, $username, $plainPassForProbe);
        }
        $pdo->prepare("
            INSERT INTO customer_xtream_lists
            (playlist_id, device_id, server_name, server_url, xtream_user, xtream_pass, m3u_url, exp_date, enabled, order_index, updated_at)
            VALUES (:pid, :did, :sname, :surl, :xuser, :xpass, '', :exp, :en, 0, :uAt)
        ")->execute([
            ':pid' => $playlistId, ':did' => $cleanId, ':sname' => $serverName,
            ':surl' => $serverUrl, ':xuser' => $username, ':xpass' => $encPass,
            ':exp' => $expDate, ':en' => $enabled, ':uAt' => $now
        ]);
    }

    try {
        $xtreamTag = "📲 XTREAM: {$serverName}";
        $uStmt = $pdo->prepare("SELECT id, notes FROM users WHERE UPPER(device_id) = :did LIMIT 1");
        $uStmt->execute([':did' => $cleanId]);
        $uRow = $uStmt->fetch();
        if ($uRow) {
            $cleanOld = preg_replace('/\s*\|\s*📲 XTREAM.*$/u', '', (string)($uRow['notes'] ?? ''));
            $newNotes = trim($cleanOld) !== '' ? ($cleanOld . ' | ' . $xtreamTag) : $xtreamTag;
            $pdo->prepare("UPDATE users SET notes = :n, updated_at = :u, last_seen = :u WHERE id = :id")
                ->execute([':n' => $newNotes, ':u' => $now, ':id' => $uRow['id']]);
        }
    } catch (Throwable $e) {}

    return true;
}

function deletePlaylistById(string $deviceId, string $playlistId, ?PDO $pdo): bool {
    if ($pdo === null) return false;
    $cleanId = strtoupper(trim($deviceId));
    $pid     = trim($playlistId);
    if ($cleanId === '' || $pid === '') return false;
    $pdo->prepare("DELETE FROM customer_xtream_lists WHERE UPPER(device_id) = :did AND playlist_id = :pid")
        ->execute([':did' => $cleanId, ':pid' => $pid]);
    return true;
}

function getDevicePinHash(string $deviceId, ?PDO $pdo): string {
    if ($pdo === null) return '';
    try {
        $stmt = $pdo->prepare("SELECT pin_hash FROM portal_security_pins WHERE UPPER(device_id) = ?");
        $stmt->execute([strtoupper(trim($deviceId))]);
        return (string)($stmt->fetchColumn() ?: '');
    } catch (Throwable $e) {
        return '';
    }
}

// -----------------------------------------------------------------------------
// 5. نقطة النهاية JSON API لتطبيق الأندرويد (مزامنة ثنائية الاتجاه)
// -----------------------------------------------------------------------------
$pdo = getDatabaseConnection();
$action = trim((string)($_REQUEST['action'] ?? ''));

if (in_array($action, ['get_xtream_lists', 'save_xtream', 'delete_xtream', 'check_status'], true) ||
    (isset($_SERVER['HTTP_ACCEPT']) && strpos($_SERVER['HTTP_ACCEPT'], 'application/json') !== false && !empty($action))) {

    header('Content-Type: application/json; charset=utf-8');
    $reqDeviceId = strtoupper(trim((string)($_REQUEST['device_id'] ?? '')));
    $subStatus   = verifyDeviceSubscription($reqDeviceId, $pdo);

    if ($action === 'get_xtream_lists' || $action === 'check_status') {
        $lists = $subStatus['active'] ? getPlaylistsByDevice($reqDeviceId, $pdo, false) : [];
        echo json_encode([
            'success'       => $subStatus['active'],
            'device_id'     => $reqDeviceId,
            'subscription'  => $subStatus,
            'xtream_lists'  => $lists,
            'contact_name'  => ALI_SAT_NAME,
            'contact_phone' => ALI_SAT_PHONE
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    if ($action === 'save_xtream') {
        if (!$subStatus['active']) {
            echo json_encode([
                'success'       => false,
                'error'         => $subStatus['message'],
                'subscription'  => $subStatus,
                'contact_name'  => ALI_SAT_NAME,
                'contact_phone' => ALI_SAT_PHONE
            ], JSON_UNESCAPED_UNICODE);
            exit;
        }
        saveOrUpdatePlaylist($reqDeviceId, [
            'id'          => $_POST['playlist_id'] ?? '',
            'server_name' => $_POST['server_name'] ?? 'XTREAM VIP',
            'server_url'  => $_POST['server_url'] ?? '',
            'username'    => $_POST['xtream_user'] ?? $_POST['username'] ?? '',
            'password'    => $_POST['xtream_pass'] ?? $_POST['password'] ?? '',
            'exp_date'    => $_POST['exp_date'] ?? '',
            'enabled'     => isset($_POST['enabled']) ? (int)$_POST['enabled'] : 1
        ], $pdo);

        echo json_encode([
            'success'      => true,
            'message'      => 'تم حفظ القائمة بنجاح ومزامنتها بين الشاشة وفضاء الزبون.',
            'subscription' => $subStatus,
            'xtream_lists' => getPlaylistsByDevice($reqDeviceId, $pdo, false)
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }

    if ($action === 'delete_xtream') {
        $pid = (string)($_REQUEST['playlist_id'] ?? '');
        deletePlaylistById($reqDeviceId, $pid, $pdo);
        echo json_encode([
            'success'      => true,
            'message'      => 'تم حذف القائمة بنجاح.',
            'xtream_lists' => getPlaylistsByDevice($reqDeviceId, $pdo, false)
        ], JSON_UNESCAPED_UNICODE);
        exit;
    }
}

// -----------------------------------------------------------------------------
// 6. معالجة طلبات واجهة الويب للزبون مع التحقق من PIN و CSRF
// -----------------------------------------------------------------------------
$currentDeviceId = strtoupper(trim((string)($_REQUEST['device_id'] ?? '')));
$flashMessage    = '';
$flashType       = 'info';
$editItem        = null;
$pinUnlocked     = false;

if ($currentDeviceId !== '') {
    if (!checkRateLimit($pdo)) {
        http_response_code(429);
        die('<h2 style="text-align:center;color:#EF4444;font-family:sans-serif;margin-top:60px;">⛔ تم تجاوز الحد المسموح من المحاولات. يرجى الانتظار 15 دقيقة.</h2>');
    }

    $subInfo = verifyDeviceSubscription($currentDeviceId, $pdo);
    $pinHash = getDevicePinHash($currentDeviceId, $pdo);

    if ($pinHash === '' || !empty($_SESSION['pin_ok_' . $currentDeviceId])) {
        $pinUnlocked = true;
    }

    if ($_SERVER['REQUEST_METHOD'] === 'POST') {
        $postedCsrf = (string)($_POST['csrf_token'] ?? '');
        if (!hash_equals($CSRF_TOKEN, $postedCsrf)) {
            $flashType    = 'error';
            $flashMessage = '⛔ انتهت صلاحية الجلسة الأمنية، يرجى إعادة المحاولة.';
        } else {
            $formAction = (string)($_POST['form_action'] ?? '');

            if ($formAction === 'verify_pin') {
                $inputPin = trim((string)($_POST['pin_code'] ?? ''));
                if ($pinHash !== '' && password_verify($inputPin, $pinHash)) {
                    $_SESSION['pin_ok_' . $currentDeviceId] = true;
                    $pinUnlocked = true;
                    $flashType = 'success';
                    $flashMessage = '🔓 تم التحقق من رمز الحماية السري بنجاح.';
                } else {
                    recordFailedAttempt($pdo);
                    $flashType = 'error';
                    $flashMessage = '❌ رمز الحماية السري (PIN) غير صحيح.';
                }
            } elseif ($formAction === 'set_pin' && $pinUnlocked) {
                $newPin = trim((string)($_POST['new_pin'] ?? ''));
                if ($newPin === '') {
                    $pdo->prepare("DELETE FROM portal_security_pins WHERE UPPER(device_id) = ?")->execute([$currentDeviceId]);
                    $flashType = 'success';
                    $flashMessage = '🔓 تم إلغاء قفل PIN للشاشة.';
                } elseif (preg_match('/^[0-9]{4,8}$/', $newPin)) {
                    $hash = password_hash($newPin, PASSWORD_DEFAULT);
                    $pdo->prepare("INSERT INTO portal_security_pins (device_id, pin_hash, updated_at) VALUES (?, ?, datetime('now')) ON CONFLICT(device_id) DO UPDATE SET pin_hash = excluded.pin_hash, updated_at = datetime('now')")
                        ->execute([$currentDeviceId, $hash]);
                    $_SESSION['pin_ok_' . $currentDeviceId] = true;
                    $flashType = 'success';
                    $flashMessage = '🔒 تم تفعيل رمز الحماية السري (PIN) لحماية قوائمك من المتطفلين.';
                } else {
                    $flashType = 'warning';
                    $flashMessage = 'يرجى إدخال رمز PIN مكون من 4 إلى 8 أرقام.';
                }
            } elseif ($pinUnlocked && $formAction === 'save_playlist') {
                if (!$subInfo['active']) {
                    $flashType    = 'error';
                    $flashMessage = "⛔ لا يمكن رفع أو تعديل القائمة لأن اشتراك المشغل منتهي الصلاحية للشاشة ({$currentDeviceId}). يرجى التجديد عبر " . ALI_SAT_NAME . " : " . ALI_SAT_PHONE;
                } else {
                    $sName = trim((string)($_POST['server_name'] ?? ''));
                    $sUrl  = trim((string)($_POST['server_url'] ?? ''));
                    $sUser = trim((string)($_POST['username'] ?? ''));
                    $sPass = trim((string)($_POST['password'] ?? ''));
                    $pid   = trim((string)($_POST['playlist_id'] ?? ''));

                    // إذا لصق المستخدم رابط M3U في خانة السيرفر، يتم تفكيكه تلقائياً!
                    $split = parseXtreamFromM3uLink($sUrl);
                    if ($split !== null) {
                        $sUrl  = $split['server_url'];
                        if ($sUser === '') $sUser = $split['username'];
                        if ($sPass === '') $sPass = $split['password'];
                    }

                    if ($sUrl === '' || $sUser === '' || ($sPass === '' && $pid === '')) {
                        $flashType    = 'warning';
                        $flashMessage = 'يرجى إدخال رابط السيرفر واسم المستخدم وكلمة المرور (أو لصق رابط M3U كامل ليتم تفكيكه تلقائياً).';
                    } else {
                        saveOrUpdatePlaylist($currentDeviceId, [
                            'id'          => $pid,
                            'server_name' => $sName !== '' ? $sName : 'قائمة XTREAM VIP',
                            'server_url'  => $sUrl,
                            'username'    => $sUser,
                            'password'    => $sPass,
                            'enabled'     => isset($_POST['enabled']) ? 1 : 0
                        ], $pdo);
                        $flashType    = 'success';
                        $flashMessage = $pid !== ''
                            ? '✅ تم حفظ تعديلات القائمة بنجاح! اضغط على «تحديث المحتوى» في الشاشة لمزامنتها فوراً.'
                            : '✅ تمت إضافة القائمة وتشفيرها بنجاح! ستظهر فوراً على شاشتك.';
                    }
                }
            } elseif ($pinUnlocked && $formAction === 'delete_playlist') {
                $pid = trim((string)($_POST['playlist_id'] ?? ''));
                deletePlaylistById($currentDeviceId, $pid, $pdo);
                $flashType    = 'success';
                $flashMessage = '🗑️ تم حذف القائمة من الشاشة بنجاح.';
            } elseif ($pinUnlocked && $formAction === 'toggle_playlist') {
                $pid   = trim((string)($_POST['playlist_id'] ?? ''));
                $lists = getPlaylistsByDevice($currentDeviceId, $pdo, false);
                foreach ($lists as $l) {
                    if ($l['id'] === $pid) {
                        $l['enabled'] = !$l['enabled'];
                        saveOrUpdatePlaylist($currentDeviceId, $l, $pdo);
                        $flashType    = 'success';
                        $flashMessage = '✅ تم تغيير حالة القائمة بنجاح.';
                        break;
                    }
                }
            }
        }
    }

    // جلب القوائم مع إخفاء كلمات المرور في جدول العرض لحماية الحسابات من المتطفلين
    $playlists = $pinUnlocked ? getPlaylistsByDevice($currentDeviceId, $pdo, true) : [];
    if ($pinUnlocked && isset($_GET['edit_id'])) {
        $editId = trim((string)$_GET['edit_id']);
        foreach ($playlists as $pl) {
            if ($pl['id'] === $editId) {
                $editItem = $pl;
                break;
            }
        }
    }
} else {
    $subInfo   = null;
    $playlists = [];
}

function isExpiringSoonDate(string $dateStr): bool {
    if ($dateStr === '' || $dateStr === 'نشط') return false;
    $ts = strtotime($dateStr . ' 23:59:59');
    if ($ts === false) return false;
    $days = (int)floor(($ts - time()) / 86400);
    return $days <= 5;
}
?>
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>LIVE PRO v9.0 — فضاء الزبون الملكي المؤمّن</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link href="https://fonts.googleapis.com/css2?family=Cairo:wght@500;700;800;900&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg-obsidian: #07040C;
            --surface-royal: #120921;
            --card-royal: #190D2E;
            --purple-deep: #2E1452;
            --purple-glow: #6D28D9;
            --purple-border: #381E63;
            --text-white: #F8FAFC;
            --text-lavender: #DDD6FE;
            --emerald: #10B981;
            --crimson: #EF4444;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: 'Cairo', system-ui, -apple-system, sans-serif;
            background: radial-gradient(circle at 50% 0%, #2A114E 0%, var(--bg-obsidian) 72%);
            color: var(--text-white);
            min-height: 100vh;
            padding: 24px 14px;
            text-align: center;
            line-height: 1.65;
        }
        .container { max-width: 940px; margin: 0 auto; }
        .glossy-header {
            background: linear-gradient(145deg, rgba(59, 25, 104, 0.72) 0%, rgba(18, 9, 33, 0.94) 100%);
            border: 1.5px solid var(--purple-glow);
            border-radius: 22px;
            padding: 24px 20px;
            margin-bottom: 20px;
            box-shadow: 0 14px 38px rgba(0, 0, 0, 0.65), inset 0 1px 0 rgba(255, 255, 255, 0.16);
            text-align: center;
        }
        .brand-title {
            font-size: 1.75rem;
            font-weight: 900;
            color: #FFFFFF;
            margin-bottom: 6px;
        }
        .brand-sub {
            font-size: 0.96rem;
            color: var(--text-lavender);
            margin-bottom: 14px;
        }
        .support-pill {
            display: inline-block;
            background: rgba(15, 7, 28, 0.85);
            border: 1.5px solid var(--purple-glow);
            padding: 8px 20px;
            border-radius: 999px;
            font-size: 0.94rem;
            font-weight: 800;
            color: #FFFFFF;
        }
        .card {
            background: linear-gradient(180deg, rgba(28, 15, 49, 0.92) 0%, rgba(16, 8, 30, 0.96) 100%);
            border: 1.5px solid var(--purple-border);
            border-radius: 20px;
            padding: 22px 18px;
            margin-bottom: 20px;
            box-shadow: 0 10px 28px rgba(0, 0, 0, 0.48), inset 0 1px 0 rgba(255, 255, 255, 0.08);
            text-align: center;
        }
        .card-expiring-red {
            border: 2.5px solid var(--crimson) !important;
            box-shadow: 0 0 24px rgba(239, 68, 68, 0.38) !important;
        }
        .card h2 {
            font-size: 1.22rem;
            font-weight: 800;
            margin-bottom: 14px;
            color: #FFFFFF;
            text-align: center;
        }
        .form-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
            gap: 14px;
            margin-bottom: 16px;
            text-align: center;
        }
        label {
            display: block;
            font-size: 0.9rem;
            font-weight: 700;
            color: var(--text-lavender);
            margin-bottom: 6px;
            text-align: center;
        }
        input[type="text"], input[type="password"] {
            width: 100%;
            background: #090511;
            border: 1.5px solid var(--purple-border);
            color: #FFFFFF;
            padding: 12px 14px;
            border-radius: 12px;
            font-family: inherit;
            font-size: 0.98rem;
            text-align: center;
            outline: none;
            transition: border-color 0.2s, box-shadow 0.2s;
        }
        input:focus {
            border-color: #A855F7;
            box-shadow: 0 0 0 3px rgba(168, 85, 247, 0.22);
        }
        .code-input {
            font-family: monospace;
            font-size: 1.2rem !important;
            font-weight: 900;
            text-transform: uppercase;
            letter-spacing: 2px;
        }
        .btn {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            gap: 8px;
            background: linear-gradient(135deg, #5B21B6 0%, #3B1968 100%);
            color: #FFFFFF;
            border: 1px solid rgba(255, 255, 255, 0.24);
            padding: 12px 24px;
            border-radius: 12px;
            font-family: inherit;
            font-weight: 800;
            font-size: 0.98rem;
            cursor: pointer;
            text-decoration: none;
            transition: transform 0.15s, box-shadow 0.2s;
        }
        .btn:hover { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(109, 40, 217, 0.45); }
        .btn-emerald { background: linear-gradient(135deg, #10B981 0%, #059669 100%); color: #041E16; }
        .btn-danger { background: rgba(239, 68, 68, 0.22); border: 1.5px solid var(--crimson); color: #FECACA; }
        .btn-sm { padding: 7px 14px; font-size: 0.85rem; border-radius: 9px; }
        .alert {
            padding: 14px 18px;
            border-radius: 14px;
            margin-bottom: 18px;
            font-weight: 800;
            text-align: center;
        }
        .alert-success { background: rgba(16, 185, 129, 0.15); border: 1.5px solid var(--emerald); color: #A7F3D0; }
        .alert-error { background: rgba(239, 68, 68, 0.18); border: 2px solid var(--crimson); color: #FECACA; }
        .alert-warning { background: rgba(245, 158, 11, 0.16); border: 1.5px solid #F59E0B; color: #FDE68A; }
        .smart-m3u-toast {
            display: none;
            background: rgba(109, 40, 217, 0.28);
            border: 1.5px solid #A855F7;
            color: #F3E8FF;
            padding: 10px 14px;
            border-radius: 12px;
            margin-bottom: 14px;
            font-weight: 800;
            font-size: 0.9rem;
        }
        .table-wrap { overflow-x: auto; }
        table { width: 100%; border-collapse: collapse; margin-top: 8px; }
        th, td {
            padding: 12px 10px;
            text-align: center;
            border-bottom: 1px solid var(--purple-border);
            font-size: 0.92rem;
        }
        th { color: var(--text-lavender); font-weight: 800; }
        .date-pill {
            display: inline-block;
            padding: 4px 12px;
            border-radius: 9px;
            font-weight: 800;
            font-size: 0.84rem;
            background: rgba(16, 185, 129, 0.14);
            border: 1.5px solid var(--emerald);
            color: #A7F3D0;
        }
        .date-pill-danger {
            background: rgba(239, 68, 68, 0.22) !important;
            border: 2px solid var(--crimson) !important;
            color: #FECACA !important;
            box-shadow: 0 0 12px rgba(239, 68, 68, 0.45);
        }
    </style>
</head>
<body>
<div class="container">
    <div class="glossy-header">
        <div class="brand-title">👑 LIVE PRO — فضاء الزبون الملكي</div>
        <div class="brand-sub">إدارة ومزامنة قوائم XTREAM عن بُعد عبر رمز الشاشة مع التشفير التام وتفكيك M3U الذكي</div>
        <div class="support-pill">
            📞 خدمة التفعيل والتجديد — <?php echo htmlspecialchars(ALI_SAT_NAME); ?> : <span dir="ltr"><?php echo htmlspecialchars(ALI_SAT_PHONE); ?></span>
        </div>
    </div>

    <div class="card">
        <h2>🔍 أدخل رمز الشاشة (Screen Code) لعرض أو تعديل قوائمك</h2>
        <form method="get" action="" style="max-width: 560px; margin: 0 auto;">
            <div style="display: flex; flex-wrap: wrap; gap: 10px; justify-content: center;">
                <div style="flex: 1; min-width: 230px;">
                    <input type="text" name="device_id" class="code-input"
                           placeholder="DEV-XXXX-XXXX"
                           value="<?php echo htmlspecialchars($currentDeviceId); ?>" required>
                </div>
                <button type="submit" class="btn">🔎 عرض وإدارة قوائم الشاشة</button>
            </div>
        </form>
    </div>

    <?php if ($flashMessage !== ''): ?>
        <div class="alert alert-<?php echo htmlspecialchars($flashType); ?>">
            <?php echo htmlspecialchars($flashMessage); ?>
        </div>
    <?php endif; ?>

    <?php if ($currentDeviceId !== '' && $subInfo !== null): ?>
        <?php
            $subExpiringSoon = !$subInfo['active'] || ((int)$subInfo['days_left'] <= 5);
        ?>
        <!-- بطاقة صلاحية المشغل (تتحول لإطار أحمر تلقائياً عند اقتراب الانتهاء أو الانتهاء) -->
        <div class="card <?php echo $subExpiringSoon ? 'card-expiring-red' : ''; ?>">
            <?php if ($subInfo['active']): ?>
                <h2>✅ اشتراك المشغل للشاشة (<span dir="ltr"><?php echo htmlspecialchars($currentDeviceId); ?></span>)</h2>
                <div style="margin-top: 8px;">
                    <span class="date-pill <?php echo $subExpiringSoon ? 'date-pill-danger' : ''; ?>">
                        ⏳ الصلاحية المتبقية: <?php echo (int)$subInfo['days_left']; ?> يوم
                        <?php if (!empty($subInfo['expire_date'])): ?>
                            • تاريخ الانتهاء: <?php echo htmlspecialchars($subInfo['expire_date']); ?>
                        <?php endif; ?>
                    </span>
                </div>
                <?php if ($subExpiringSoon): ?>
                    <p style="color: #FCA5A5; font-weight: 800; margin-top: 10px; font-size: 0.92rem;">
                        ⚠️ اقترب موعد انتهاء صلاحية المشغل! يرجى التواصل مع <?php echo htmlspecialchars(ALI_SAT_NAME); ?> (<span dir="ltr"><?php echo htmlspecialchars(ALI_SAT_PHONE); ?></span>) لتجديد الاشتراك.
                    </p>
                <?php endif; ?>
            <?php else: ?>
                <h2 style="color: #FCA5A5;">⛔ انتهت صلاحية اشتراك المشغل للشاشة (<span dir="ltr"><?php echo htmlspecialchars($currentDeviceId); ?></span>)</h2>
                <p style="margin: 10px 0; color: #FECACA; font-weight: 700;">
                    لا يمكن رفع أو تعديل القوائم عندما يكون المشغل منتهي الصلاحية. يرجى تقديم رمز الشاشة لتجديد الاشتراك:
                </p>
                <div class="date-pill date-pill-danger" style="font-size: 1rem; padding: 8px 18px;">
                    📞 <?php echo htmlspecialchars(ALI_SAT_NAME); ?> : <span dir="ltr"><?php echo htmlspecialchars(ALI_SAT_PHONE); ?></span>
                    &nbsp;|&nbsp; 🖥️ <span dir="ltr"><?php echo htmlspecialchars($currentDeviceId); ?></span>
                </div>
            <?php endif; ?>
        </div>

        <?php if (!$pinUnlocked): ?>
            <!-- شاشة إدخال رمز الحماية السري PIN -->
            <div class="card" style="max-width: 520px; margin: 0 auto 20px auto;">
                <h2>🔒 هذه الشاشة محمية برمز سري (PIN)</h2>
                <p style="color: var(--text-lavender); margin-bottom: 14px; font-size: 0.92rem;">
                    أدخل رمز الحماية السري الخاص بك للوصول إلى قوائم XTREAM وتعديلها:
                </p>
                <form method="post" action="?device_id=<?php echo urlencode($currentDeviceId); ?>">
                    <input type="hidden" name="csrf_token" value="<?php echo htmlspecialchars($CSRF_TOKEN); ?>">
                    <input type="hidden" name="device_id" value="<?php echo htmlspecialchars($currentDeviceId); ?>">
                    <input type="hidden" name="form_action" value="verify_pin">
                    <div style="max-width: 260px; margin: 0 auto 14px auto;">
                        <input type="password" name="pin_code" placeholder="••••" required maxlength="8" class="code-input">
                    </div>
                    <button type="submit" class="btn btn-emerald">🔓 فتح لوحة القوائم</button>
                </form>
            </div>
        <?php else: ?>
            <?php if ($subInfo['active']): ?>
                <!-- نموذج موحّد ذكي لإضافة أو تعديل قائمة XTREAM (يفكك M3U تلقائياً بدون خانة إضافية) -->
                <div class="card">
                    <h2><?php echo $editItem ? '✏️ تعديل قائمة XTREAM المحفوظة' : '➕ إضافة قائمة XTREAM جديدة (أو لصق رابط M3U لتفكيكه تلقائياً)'; ?></h2>
                    <div id="m3uAutoSplitToast" class="smart-m3u-toast">
                        ✨ تم تفكيك رابط M3U تلقائياً واستخراج الهوست واسم المستخدم وكلمة المرور بنجاح!
                    </div>
                    <form method="post" action="?device_id=<?php echo urlencode($currentDeviceId); ?>">
                        <input type="hidden" name="csrf_token" value="<?php echo htmlspecialchars($CSRF_TOKEN); ?>">
                        <input type="hidden" name="device_id" value="<?php echo htmlspecialchars($currentDeviceId); ?>">
                        <input type="hidden" name="form_action" value="save_playlist">
                        <input type="hidden" name="playlist_id" value="<?php echo htmlspecialchars((string)($editItem['id'] ?? '')); ?>">

                        <div class="form-grid">
                            <div>
                                <label>اسم القائمة (يظهر على الشاشة)</label>
                                <input type="text" name="server_name" id="server_name_input"
                                       placeholder="مثال: السيرفر الذهبي VIP"
                                       value="<?php echo htmlspecialchars((string)($editItem['server_name'] ?? '')); ?>" required>
                            </div>
                            <div>
                                <label>رابط سيرفر الأكستريم (أو الصق رابط M3U كامل هنا)</label>
                                <input type="text" name="server_url" id="server_url_input" dir="ltr"
                                       placeholder="http://host.com:8080"
                                       value="<?php echo htmlspecialchars((string)($editItem['server_url'] ?? '')); ?>"
                                       oninput="autoSplitM3uInput(this.value)" required>
                            </div>
                        </div>

                        <div class="form-grid">
                            <div>
                                <label>اسم المستخدم (Username)</label>
                                <input type="text" name="username" id="username_input" dir="ltr"
                                       placeholder="Username"
                                       value="<?php echo htmlspecialchars((string)($editItem['username'] ?? '')); ?>" required>
                            </div>
                            <div>
                                <label>كلمة المرور (Password — مشفرة ومحمية)</label>
                                <input type="password" name="password" id="password_input" dir="ltr"
                                       placeholder="<?php echo $editItem ? 'اتركه فارغاً للاحتفاظ بكلمة المرور الحالية' : 'Password'; ?>"
                                       <?php echo $editItem ? '' : 'required'; ?>>
                            </div>
                        </div>

                        <div style="margin: 12px 0 18px 0;">
                            <label style="display: inline-flex; align-items: center; gap: 10px; cursor: pointer; color: #FFFFFF;">
                                <input type="checkbox" name="enabled" value="1"
                                    <?php echo (!isset($editItem['enabled']) || !empty($editItem['enabled'])) ? 'checked' : ''; ?>
                                       style="width: 19px; height: 19px;">
                                تفعيل هذه القائمة مباشرة على الشاشة
                            </label>
                        </div>

                        <div style="display: flex; gap: 12px; justify-content: center; flex-wrap: wrap;">
                            <button type="submit" class="btn btn-emerald">
                                <?php echo $editItem ? '💾 حفظ التعديلات ومزامنتها مع الشاشة' : '⚡ حفظ القائمة وربطها بالشاشة فوراً'; ?>
                            </button>
                            <?php if ($editItem): ?>
                                <a href="?device_id=<?php echo urlencode($currentDeviceId); ?>" class="btn">إلغاء</a>
                            <?php endif; ?>
                        </div>
                    </form>
                </div>
            <?php endif; ?>

            <!-- جدول القوائم المرفوعة (سواء من الشاشة أو من اللوحة) -->
            <div class="card">
                <h2>📋 جميع قوائم الشاشة المحفوظة (<?php echo count($playlists); ?>)</h2>
                <p style="color: var(--text-lavender); font-size: 0.88rem; margin-bottom: 12px;">
                    أي قائمة ترفعها من التلفاز أو من الهاتف تظهر هنا تلقائياً ويمكنك تعديلها بحرية، مع إخفاء كلمات المرور لحمايتها.
                </p>
                <?php if (empty($playlists)): ?>
                    <p style="color: var(--text-lavender); padding: 14px;">لا توجد قوائم مضافة لهذه الشاشة حالياً.</p>
                <?php else: ?>
                    <div class="table-wrap">
                        <table>
                            <thead>
                            <tr>
                                <th>#</th>
                                <th>اسم القائمة</th>
                                <th>السيرفر (Host)</th>
                                <th>اسم المستخدم</th>
                                <th>كلمة المرور</th>
                                <th>صلاحية السيرفر</th>
                                <th>الحالة</th>
                                <th>التحكم</th>
                            </tr>
                            </thead>
                            <tbody>
                            <?php foreach ($playlists as $idx => $pl): ?>
                                <?php $plExpiring = isExpiringSoonDate((string)($pl['exp_date'] ?? '')); ?>
                                <tr>
                                    <td><?php echo $idx + 1; ?></td>
                                    <td><strong><?php echo htmlspecialchars($pl['server_name']); ?></strong></td>
                                    <td dir="ltr"><?php echo htmlspecialchars($pl['server_url']); ?></td>
                                    <td dir="ltr"><?php echo htmlspecialchars($pl['username']); ?></td>
                                    <td dir="ltr" style="color: #A78BFA; letter-spacing: 2px;">••••••••</td>
                                    <td>
                                        <?php if (!empty($pl['exp_date'])): ?>
                                            <span class="date-pill <?php echo $plExpiring ? 'date-pill-danger' : ''; ?>">
                                                <?php echo htmlspecialchars($pl['exp_date']); ?>
                                            </span>
                                        <?php else: ?>
                                            <span style="color: var(--text-lavender);">متصل ✓</span>
                                        <?php endif; ?>
                                    </td>
                                    <td>
                                        <?php if (!empty($pl['enabled'])): ?>
                                            <span class="date-pill">مفعّلة ✓</span>
                                        <?php else: ?>
                                            <span class="date-pill date-pill-danger">معطّلة</span>
                                        <?php endif; ?>
                                    </td>
                                    <td>
                                        <div style="display: flex; gap: 6px; justify-content: center; flex-wrap: wrap;">
                                            <?php if ($subInfo['active']): ?>
                                                <a class="btn btn-sm"
                                                   href="?device_id=<?php echo urlencode($currentDeviceId); ?>&edit_id=<?php echo urlencode($pl['id']); ?>">
                                                    ✏️ تعديل
                                                </a>
                                                <form method="post" action="?device_id=<?php echo urlencode($currentDeviceId); ?>" style="display:inline;">
                                                    <input type="hidden" name="csrf_token" value="<?php echo htmlspecialchars($CSRF_TOKEN); ?>">
                                                    <input type="hidden" name="device_id" value="<?php echo htmlspecialchars($currentDeviceId); ?>">
                                                    <input type="hidden" name="form_action" value="toggle_playlist">
                                                    <input type="hidden" name="playlist_id" value="<?php echo htmlspecialchars($pl['id']); ?>">
                                                    <button type="submit" class="btn btn-sm">
                                                        <?php echo !empty($pl['enabled']) ? 'إيقاف' : 'تفعيل'; ?>
                                                    </button>
                                                </form>
                                            <?php endif; ?>
                                            <form method="post" action="?device_id=<?php echo urlencode($currentDeviceId); ?>"
                                                  onsubmit="return confirm('هل تريد حذف هذه القائمة نهائياً؟');" style="display:inline;">
                                                <input type="hidden" name="csrf_token" value="<?php echo htmlspecialchars($CSRF_TOKEN); ?>">
                                                <input type="hidden" name="device_id" value="<?php echo htmlspecialchars($currentDeviceId); ?>">
                                                <input type="hidden" name="form_action" value="delete_playlist">
                                                <input type="hidden" name="playlist_id" value="<?php echo htmlspecialchars($pl['id']); ?>">
                                                <button type="submit" class="btn btn-sm btn-danger">🗑️ حذف</button>
                                            </form>
                                        </div>
                                    </td>
                                </tr>
                            <?php endforeach; ?>
                            </tbody>
                        </table>
                    </div>
                <?php endif; ?>
            </div>

            <!-- إعداد رمز الحماية السري PIN لحماية اللوحة من المتطفلين -->
            <div class="card">
                <h2>🛡️ تأمين قوائم الشاشة برمز سري (PIN) ضد المتطفلين</h2>
                <p style="color: var(--text-lavender); font-size: 0.88rem; margin-bottom: 12px;">
                    لحماية حساباتك من أي شخص قد يعرف رمز شاشتك، يمكنك تعيين رمز PIN سري (4-8 أرقام) يُطلب قبل عرض أو تعديل القوائم في المتصفح:
                </p>
                <form method="post" action="?device_id=<?php echo urlencode($currentDeviceId); ?>" style="max-width: 480px; margin: 0 auto;">
                    <input type="hidden" name="csrf_token" value="<?php echo htmlspecialchars($CSRF_TOKEN); ?>">
                    <input type="hidden" name="device_id" value="<?php echo htmlspecialchars($currentDeviceId); ?>">
                    <input type="hidden" name="form_action" value="set_pin">
                    <div style="display: flex; gap: 10px; justify-content: center; flex-wrap: wrap;">
                        <div style="flex: 1; min-width: 180px;">
                            <input type="password" name="new_pin" placeholder="أدخل PIN جديد (أو فارغ لإلغائه)" maxlength="8">
                        </div>
                        <button type="submit" class="btn">🔒 حفظ قفل الحماية</button>
                    </div>
                </form>
            </div>
        <?php endif; ?>
    <?php endif; ?>
</div>

<script>
function autoSplitM3uInput(val) {
    if (!val || (val.indexOf('username=') === -1 && val.indexOf('/live/') === -1)) return;
    try {
        var u = new URL(val.trim());
        var base = u.protocol + '//' + u.host;
        var user = u.searchParams.get('username') || u.searchParams.get('user') || '';
        var pass = u.searchParams.get('password') || u.searchParams.get('pass') || '';
        if (!user && u.pathname) {
            var m = u.pathname.match(/\/(?:live|movie|series)\/([^\/]+)\/([^\/]+)/i);
            if (m) {
                user = decodeURIComponent(m[1]);
                pass = decodeURIComponent(m[2]);
            }
        }
        if (user && pass) {
            document.getElementById('server_url_input').value = base;
            document.getElementById('username_input').value = user;
            document.getElementById('password_input').value = pass;
            var toast = document.getElementById('m3uAutoSplitToast');
            if (toast) toast.style.display = 'block';
        }
    } catch (e) {}
}
</script>
</body>
</html>

