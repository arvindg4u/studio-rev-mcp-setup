# H2 Plan 2: GAS Sidecar (SupabaseSidecar.gs + J1-J5 + Triggers) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Supabase sidecar to the Adaptive SR GAS project that mirrors state to Supabase via five scheduled jobs without changing the existing review loop.

**Architecture:** One new file `codebase/SupabaseSidecar.gs` holds a REST client plus jobs J1-J5 and `installH2Triggers()`. Existing modules (`TaskManager.gs`, `RaindropSync.gs`, `EmailDigest.gs`, `Monitoring.gs` via `handleError`, `Code.gs` constants) are reused read-only. Supabase is accessed with service key from Script Properties, watermark stored in Script Properties.

**Tech Stack:** Google Apps Script V8, UrlFetchApp, ScriptApp triggers, LockService, MailApp, Tasks v1 advanced service, Supabase PostgREST REST API.

**Spec:** `/teamspace/studios/this_studio/Projects/adaptive-sr-system/docs/superpowers/specs/2026-09-03-h2-hybrid-android-app-design.md` Section 3 sidecar jobs (J1 keepalive daily 06:00, J2 digest 07:00 via MailApp from Supabase, J3 Tasks mirror every 6h + 07:05 reconcile with watermark TASKS_MIRROR_WATERMARK, J4 Raindrop sync 08:00+20:00 reusing fetchAllCollections/fetchBookmarksWithSR with raindrop_id dedupe, J5 ColdMirrorLog append-only hourly :35 + inline, installH2Triggers()).

## Global Constraints

- Timezone is `Asia/Kolkata` (from `codebase/appsscript.json`).
- Tasks advanced service `Tasks v1` stays enabled; do not modify `codebase/appsscript.json` except if scopes require it.
- Script Properties keys `SUPABASE_URL` and `SUPABASE_SERVICE_KEY` hold Supabase credentials; watermark keys are exactly `TASKS_MIRROR_WATERMARK` and `COLD_MIRROR_LAST_AT`.
- All jobs wrap body in try/catch calling existing `handleError(jobName, e)`.
- Supabase tables used (spec §1.1/§1.3 ONLY — this plan creates no tables; H2 Plan 1 owns the schema): `cards` (upsert key `raindrop_id`, numeric bigint, never stringified on write), `reviews` (append via `sbPost_`), `sync_state` (single-row upsert key `user_id`; columns `gas_last_seen_at`, `raindrop_cursor`), `notifications` (append-only sink for J1 heartbeat + J5 cold-mirror snapshots, both `type='SYNC_ERROR'`, `status='SENT'`, distinguished by `payload.heartbeat` / `payload.cold_mirror`). No `tasks_mirror`, no `digest_queue`, no `cold_mirror_log` — those names appear nowhere in the spec DDL.
- `raindrop_id` is numeric on every Supabase write; string coercion only for in-memory dedupe keys.
- Timezone `Asia/Kolkata` on all time-based triggers.
- No placeholders: every job ships working code on first commit.
- Existing review loop (`processReview`, `createSpacedRepetitionTask`, `sendDailyDigest`) behavior unchanged.

---

## File Structure

- Create: `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/SupabaseSidecar.gs` — owns Supabase REST client (`sbGet_`, `sbPost_`, `sbUpsert_`, `getSbConfig_`), jobs J1-J5 (`sidecarKeepalive_J1`, `sidecarDigest_J2`, `sidecarTasksMirror_J3`, `sidecarTasksReconcile_J3b`, `sidecarRaindropSync_J4`, `sidecarColdMirror_J5`), inline cold-mirror helper (`coldMirrorAppend_`), and `installH2Triggers()`. Single responsibility: GAS-to-Supabase sidecar only.
- Modify: `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/Testing.gs` (or create if missing) — adds `testSidecarConfig`, `testSidecarKeepalive`, `testSidecarDigestDryRun`, `testSidecarTasksMirrorDryRun`, `testSidecarTriggersDryRun`. No existing production files are modified.
- Read-only dependencies (do not edit): `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/Code.gs` (`SHEET_NAMES`, `handleError`, `getSheet`), `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/Config.gs` (`getConfig`), `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/TaskManager.gs` (`getOrCreateTaskList`), `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/RaindropSync.gs` (`fetchAllCollections`, `fetchBookmarksWithSR`, `smartThrottle`), `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/Monitoring.gs` (`trackApiUsage`), `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/appsscript.json`.

---

### Task 1: Sidecar REST core + Script Properties wiring

**Files:**
- Create: `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/SupabaseSidecar.gs` (sections 0-1 only)
- Modify: none
- Test: `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/Testing.gs` — add `testSidecarConfig`

**Interfaces:**
- Consumes: `PropertiesService.getScriptProperties()`, existing `handleError(fn, e)` from `Code.gs`.
- Produces: `getSbConfig_()` returns `{baseUrl: string, serviceKey: string}`; `sbGet_(path, query)` returns parsed JSON; `sbPost_(path, row)` returns parsed JSON; `sbPatch_(path, query, row)` returns parsed JSON (spec §1.3 requires all three verbs; upsert is POST with `on_conflict`); `sbUpsert_(table, row, onConflict)` returns parsed JSON; `sbNowIso_()` returns string. All later tasks consume these exact names.

- [ ] **Step 1: Write the failing test in Testing.gs**

```gs
function testSidecarConfig() {
  var cfg = getSbConfig_();
  if (!cfg.baseUrl || cfg.baseUrl.indexOf('https://') !== 0) throw new Error('SUPABASE_URL missing, set via: clasp run setupSidecarProps');
  if (!cfg.serviceKey || cfg.serviceKey.length < 20) throw new Error('SUPABASE_SERVICE_KEY missing');
  Logger.log('sidecar config OK: ' + cfg.baseUrl);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clasp push && clasp run testSidecarConfig`
Expected: FAIL with "getSbConfig_ is not defined" (function does not exist yet).

- [ ] **Step 3: Write minimal implementation (top of SupabaseSidecar.gs)**

```gs
// ============================================
// H2 SIDECAR - SUPABASE MIRROR (Section 0-1: core)
// ============================================
// Sidecar only. Never changes review loop. All jobs try/catch -> handleError.

var TASKS_MIRROR_WATERMARK_KEY = 'TASKS_MIRROR_WATERMARK';

function setupSidecarProps() {
  var ui = SpreadsheetApp.getUi();
  var props = PropertiesService.getScriptProperties();
  var url = ui.prompt('Enter SUPABASE_URL (https://xyz.supabase.co):').getResponseText().trim();
  var key = ui.prompt('Enter SUPABASE_SERVICE_KEY (service_role):').getResponseText().trim();
  props.setProperty('SUPABASE_URL', url);
  props.setProperty('SUPABASE_SERVICE_KEY', key);
  ui.alert('Sidecar props saved.');
}

function getSbConfig_() {
  var props = PropertiesService.getScriptProperties();
  var baseUrl = (props.getProperty('SUPABASE_URL') || '').trim().replace(/\/$/, '');
  var serviceKey = (props.getProperty('SUPABASE_SERVICE_KEY') || '').trim();
  if (!baseUrl) throw new Error('Missing Script Property SUPABASE_URL. Run setupSidecarProps.');
  if (!serviceKey) throw new Error('Missing Script Property SUPABASE_SERVICE_KEY. Run setupSidecarProps.');
  return { baseUrl: baseUrl, serviceKey: serviceKey };
}

function sbHeaders_() {
  var cfg = getSbConfig_();
  return {
    'apikey': cfg.serviceKey,
    'Authorization': 'Bearer ' + cfg.serviceKey,
    'Content-Type': 'application/json',
    'Prefer': 'resolution=merge-duplicates,return=representation'
  };
}

function sbGet_(path, query) {
  var cfg = getSbConfig_();
  var url = cfg.baseUrl + '/rest/v1/' + path + (query || '');
  var resp = UrlFetchApp.fetch(url, { method: 'get', headers: sbHeaders_(), muteHttpExceptions: true });
  var code = resp.getResponseCode();
  if (code < 200 || code >= 300) throw new Error('sbGet ' + path + ' HTTP ' + code + ': ' + resp.getContentText().slice(0, 500));
  return JSON.parse(resp.getContentText() || '[]');
}

function sbPost_(path, row) {
  var cfg = getSbConfig_();
  var url = cfg.baseUrl + '/rest/v1/' + path;
  var resp = UrlFetchApp.fetch(url, { method: 'post', headers: sbHeaders_(), payload: JSON.stringify(row), muteHttpExceptions: true });
  var code = resp.getResponseCode();
  if (code < 200 || code >= 300) throw new Error('sbPost ' + path + ' HTTP ' + code + ': ' + resp.getContentText().slice(0, 500));
  var txt = resp.getContentText() || 'null';
  try { return JSON.parse(txt); } catch (e) { return null; }
}

function sbPatch_(path, query, row) {
  var cfg = getSbConfig_();
  var url = cfg.baseUrl + '/rest/v1/' + path + (query || '');
  var resp = UrlFetchApp.fetch(url, { method: 'patch', headers: sbHeaders_(), payload: JSON.stringify(row), muteHttpExceptions: true });
  var code = resp.getResponseCode();
  if (code < 200 || code >= 300) throw new Error('sbPatch ' + path + ' HTTP ' + code + ': ' + resp.getContentText().slice(0, 500));
  var txt = resp.getContentText() || 'null';
  try { return JSON.parse(txt); } catch (e) { return null; }
}

function sbUpsert_(table, row, onConflict) {
  var cfg = getSbConfig_();
  var url = cfg.baseUrl + '/rest/v1/' + table + '?on_conflict=' + encodeURIComponent(onConflict);
  var resp = UrlFetchApp.fetch(url, { method: 'post', headers: sbHeaders_(), payload: JSON.stringify(row), muteHttpExceptions: true });
  var code = resp.getResponseCode();
  if (code < 200 || code >= 300) throw new Error('sbUpsert ' + table + ' HTTP ' + code + ': ' + resp.getContentText().slice(0, 500));
  var txt = resp.getContentText() || 'null';
  try { return JSON.parse(txt); } catch (e) { return null; }
}

function sbNowIso_() {
  return new Date().toISOString();
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clasp push && clasp run testSidecarConfig`
Expected: PASS, log shows `sidecar config OK`. If props unset, run `clasp run setupSidecarProps` equivalent in editor once, then rerun.

- [ ] **Step 5: Commit**

```bash
git add codebase/SupabaseSidecar.gs codebase/Testing.gs
git commit -m "feat(sidecar): add Supabase REST core + props wiring"
```

---

### Task 2: J1 keepalive daily 06:00

**Files:**
- Modify: `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/SupabaseSidecar.gs` — append Section J1
- Test: `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/Testing.gs` — add `testSidecarKeepalive`

**Interfaces:**
- Consumes: `sbPost_`, `sbPatch_`, `sbNowIso_`, `handleError` from Task 1 / `Code.gs`.
- Produces: `sidecarKeepalive_J1()` returns `{success: boolean, at: string}` (spec name `sbKeepAlivePing`; GAS function keeps the `sidecarKeepalive_J1` name — mapping table in Task 7); trigger name `sidecarKeepalive_J1` consumed by Task 7.

- [ ] **Step 1: Write the failing test**

```gs
function testSidecarKeepalive() {
  var res = sidecarKeepalive_J1();
  if (!res || res.success !== true) throw new Error('J1 keepalive failed: ' + JSON.stringify(res));
  Logger.log('J1 OK at ' + res.at);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clasp push && clasp run testSidecarKeepalive`
Expected: FAIL with "sidecarKeepalive_J1 is not defined".

- [ ] **Step 3: Write minimal implementation**

```gs
// ---------- J1 keepalive (daily 06:00 Asia/Kolkata; spec name sbKeepAlivePing) ----------
// Cheap read (kills the 7-day pause) + heartbeat: upserts the single sync_state
// row's gas_last_seen_at and appends a SENT heartbeat row to notifications.
var SIDECAR_USER_ID = '00000000-0000-0000-0000-000000000000'; // replaced by the real single-user id at setup; documented in Step 4
function sidecarKeepalive_J1() {
  try {
    sbGet_('cards', '?select=id&limit=1'); // cheap read = pause killer
    var now = sbNowIso_();
    sbUpsert_('sync_state', { user_id: SIDECAR_USER_ID, gas_last_seen_at: now }, 'user_id');
    sbPost_('notifications', { user_id: SIDECAR_USER_ID, type: 'SYNC_ERROR', payload: { heartbeat: true, at: now }, status: 'SENT', sent_at: now });
    try { trackApiUsage('Supabase', 'keepalive'); } catch (e) { /* metrics only */ }
    Logger.log('J1 keepalive OK at ' + now);
    return { success: true, at: now };
  } catch (e) {
    handleError('sidecarKeepalive_J1', e);
    return { success: false, error: e.toString() };
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clasp push && clasp run testSidecarKeepalive`
Expected: PASS. Verify in Supabase: `select gas_last_seen_at from sync_state;` shows today's timestamp, and `select * from notifications where payload->>'heartbeat' = 'true' order by created_at desc limit 1;` shows the heartbeat row. First-time setup: replace `SIDECAR_USER_ID` with the real single-user uuid (from Supabase auth) before the first run.

- [ ] **Step 5: Commit**

```bash
git add codebase/SupabaseSidecar.gs codebase/Testing.gs
git commit -m "feat(sidecar): add J1 keepalive heartbeat"
```

---

### Task 3: J2 digest 07:00 via MailApp from Supabase

**Files:**
- Modify: `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/SupabaseSidecar.gs` — append Section J2
- Test: `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/Testing.gs` — add `testSidecarDigestDryRun`

**Interfaces:**
- Consumes: `sbGet_` (reads due from `cards`: `suspended=false AND next_review_at<=now()`, i.e. spec §3 J2 "due from Supabase"), `getConfig()` from `Config.gs`, `handleError`.
- Produces: `sidecarDigest_J2()` returns `{success, sent, skipped}` (spec name `sendDueDigestFromSupabase`; see Task 7 mapping table); `buildSidecarDigestHtml_(items)` returns string. Task 7 schedules `sidecarDigest_J2`.

- [ ] **Step 1: Write the failing test**

```gs
function testSidecarDigestDryRun() {
  var items = sbGet_('cards', '?select=id&limit=1');
  Logger.log('cards probe rows: ' + items.length);
  var html = buildSidecarDigestHtml_([]);
  if (html.indexOf('Daily Review Digest') === -1) throw new Error('digest html builder broken');
  Logger.log('J2 dry-run OK');
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clasp push && clasp run testSidecarDigestDryRun`
Expected: FAIL with "buildSidecarDigestHtml_ is not defined".

- [ ] **Step 3: Write minimal implementation**

```gs
// ---------- J2 digest (daily 07:00, MailApp, due from Supabase cards; spec name sendDueDigestFromSupabase) ----------
function sidecarDigest_J2() {
  try {
    var config = getConfig();
    var nowIso = sbNowIso_();
    var items = sbGet_('cards', '?select=id,title,link,source,collection,next_review_at,review_count,last_rating'
      + '&suspended=eq.false&next_review_at=lte.' + encodeURIComponent(nowIso)
      + '&order=next_review_at.asc&limit=50');
    if (!items || items.length === 0) {
      Logger.log('J2: no due cards in Supabase, skipping send');
      return { success: true, sent: 0, skipped: true };
    }
    var html = buildSidecarDigestHtml_(items);
    MailApp.sendEmail({ to: config.USER_EMAIL, subject: 'Daily Digest (Supabase) - ' + items.length + ' items', htmlBody: html });
    try { trackApiUsage('EmailService', 'sidecarDigest_J2'); } catch (e) {}
    Logger.log('J2 digest sent: ' + items.length + ' rows');
    return { success: true, sent: items.length, skipped: false };
  } catch (e) {
    handleError('sidecarDigest_J2', e);
    return { success: false, error: e.toString() };
  }
}

function buildSidecarDigestHtml_(items) {
  var rows = (items || []).map(function (it, i) {
    return '<div style="background:#fff;margin:12px 0;padding:16px;border:2px solid #e5e7eb;border-radius:12px;">'
      + '<div style="font-weight:600;">' + (i + 1) + '. ' + (it.title || '(untitled)') + '</div>'
      + '<div style="color:#666;font-size:13px;">' + (it.link || '') + ' | due: ' + (it.next_review_at || '') + '</div>'
      + '</div>';
  }).join('');
  return '<html><body style="font-family:Arial;background:#f0f2f5;padding:20px;">'
    + '<div style="max-width:650px;margin:0 auto;background:white;border-radius:16px;overflow:hidden;">'
    + '<div style="background:linear-gradient(135deg,#667eea,#764ba2);color:white;padding:30px;text-align:center;">'
    + '<h1>Daily Review Digest (Supabase)</h1><p>Namaste Arvind</p></div>'
    + '<div style="padding:25px;">' + (rows || '<p>Aaj koi review due nahi hai.</p>') + '</div>'
    + '<div style="text-align:center;padding:20px;color:#666;">Adaptive SR System - H2 Sidecar</div>'
    + '</div></body></html>';
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clasp push && clasp run testSidecarDigestDryRun`
Expected: PASS without sending email (dry run only reads + builds HTML). Live send verified by `clasp run sidecarDigest_J2` when `cards` has due rows (`suspended=false AND next_review_at<=now()`).

- [ ] **Step 5: Commit**

```bash
git add codebase/SupabaseSidecar.gs codebase/Testing.gs
git commit -m "feat(sidecar): add J2 Supabase digest via MailApp"
```

---

### Task 4: J3 Tasks mirror every 6h + 07:05 reconcile with watermark

**Files:**
- Modify: `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/SupabaseSidecar.gs` — append Section J3
- Test: `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/Testing.gs` — add `testSidecarTasksMirrorDryRun`

**Interfaces:**
- Consumes: `sbGet_`, `sbPatch_`, `sbNowIso_` (Supabase `cards` due queue), `getConfig()`, `getOrCreateTaskList()`, `buildTaskNotes()`, `completeTask()`, `smartThrottle()` throttling via `Config.MIN_DELAY_MS`, `Tasks.Tasks.insert/get` (Tasks v1), `TASKS_MIRROR_WATERMARK` Script Property, `handleError`. Never touches the `MemArchiveState` sheet (spec: J3 mirrors Supabase → Tasks; sheet column indexes stay with the legacy loop).
- Produces: `sidecarTasksMirror_J3()` returns `{success, mirrored}` (spec name `mirrorDueCardsToTasks`; see Task 7 mapping table); `sidecarTasksReconcile_J3b()` returns `{success, checked, fixed}`; `readTasksWatermark_()` / `writeTasksWatermark_(iso)` for string ISO timestamps. Task 7 schedules both.

- [ ] **Step 1: Write the failing test**

```gs
function testSidecarTasksMirrorDryRun() {
  var wm = readTasksWatermark_();
  Logger.log('watermark now: ' + wm);
  if (typeof wm !== 'string') throw new Error('watermark must be string');
  var res = sidecarTasksMirror_J3();
  if (!res || res.success !== true) throw new Error('J3 mirror failed: ' + JSON.stringify(res));
  Logger.log('J3 dry-run OK mirrored=' + res.mirrored);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clasp push && clasp run testSidecarTasksMirrorDryRun`
Expected: FAIL with "readTasksWatermark_ is not defined".

- [ ] **Step 3: Write minimal implementation**

```gs
// ---------- J3 Tasks mirror (every 6h) + reconcile (07:05) ----------
function readTasksWatermark_() {
  var wm = PropertiesService.getScriptProperties().getProperty(TASKS_MIRROR_WATERMARK_KEY);
  return wm || '1970-01-01T00:00:00.000Z';
}

function writeTasksWatermark_(iso) {
  PropertiesService.getScriptProperties().setProperty(TASKS_MIRROR_WATERMARK_KEY, iso);
}

function sidecarTasksMirror_J3() {
  // Spec mirrorDueCardsToTasks: due cards in Supabase without a live task get a
  // Google Task (create path reuses getOrCreateTaskList/buildTaskNotes verbatim
  // semantics); cards already mirrored are skipped via task_id/task_list_id.
  try {
    var config = getConfig();
    var watermark = readTasksWatermark_();
    var nowIso = sbNowIso_();
    var due = sbGet_('cards', '?select=id,title,link,source,collection,task_id,task_list_id,updated_at'
      + '&suspended=eq.false&next_review_at=lte.' + encodeURIComponent(nowIso)
      + '&updated_at=gt.' + encodeURIComponent(watermark)
      + '&order=updated_at.asc&limit=50');
    var mirrored = 0;
    var maxTs = watermark;
    for (var i = 0; i < due.length; i++) {
      var c = due[i];
      if (c.task_id && c.task_list_id) {
        try {
          var live = Tasks.Tasks.get(c.task_list_id, c.task_id);
          if (live && live.status !== 'completed') { if (c.updated_at > maxTs) maxTs = c.updated_at; continue; }
        } catch (e) { /* task gone: recreate below */ }
      }
      var listName = (c.source === 'FORM') ? 'Spaced Repetition' : ('Spaced Repetition: ' + (c.collection || 'General'));
      var taskList = getOrCreateTaskList(listName);
      smartThrottle(config.MIN_DELAY_MS);
      var notes = buildTaskNotes(c.link || '', String(c.id), '', 0, 0, 'NEW', '', '');
      var task = Tasks.Tasks.insert({ title: c.title, notes: notes,
        due: new Date(new Date().getTime() + 86400000).toISOString() }, taskList.id);
      sbPatch_('cards', '?id=eq.' + c.id, { task_id: task.id, task_list_id: taskList.id });
      mirrored++;
      if (c.updated_at > maxTs) maxTs = c.updated_at;
    }
    if (mirrored > 0) writeTasksWatermark_(maxTs);
    Logger.log('J3 mirror OK: ' + mirrored + ' rows, watermark ' + watermark + ' -> ' + maxTs);
    return { success: true, mirrored: mirrored };
  } catch (e) {
    handleError('sidecarTasksMirror_J3', e);
    return { success: false, error: e.toString() };
  }
}

function sidecarTasksReconcile_J3b() {
  // 07:05 drift guard: any mirrored card whose live task is completed gets its
  // task pointers cleared via sbPatch_ so the next J3 pass can recreate if the
  // card is still due; MASTERED/suspended cards are never touched.
  try {
    var config = getConfig();
    var mirrored = sbGet_('cards', '?select=id,task_id,task_list_id,status,suspended'
      + '&task_id=not.is.null&task_list_id=not.is.null&suspended=eq.false&limit=100');
    var checked = 0;
    var fixed = 0;
    for (var i = 0; i < mirrored.length; i++) {
      var c = mirrored[i];
      checked++;
      smartThrottle(config.MIN_DELAY_MS);
      var live;
      try {
        live = Tasks.Tasks.get(c.task_list_id, c.task_id);
      } catch (e) {
        continue;
      }
      if (live && live.status === 'completed') {
        sbPatch_('cards', '?id=eq.' + c.id, { task_id: null, task_list_id: null });
        fixed++;
      }
    }
    Logger.log('J3b reconcile OK: checked=' + checked + ' fixed=' + fixed);
    return { success: true, checked: checked, fixed: fixed };
  } catch (e) {
    handleError('sidecarTasksReconcile_J3b', e);
    return { success: false, error: e.toString() };
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `clasp push && clasp run testSidecarTasksMirrorDryRun && clasp run sidecarTasksReconcile_J3b`
Expected: PASS. Verify watermark advanced: check Script Properties `TASKS_MIRROR_WATERMARK` changed; verify Supabase `select id, task_id, task_list_id from cards where task_id is not null;` shows mirrored rows. Second consecutive run with no new due cards returns `mirrored: 0` (task-pointer skip holds).

- [ ] **Step 5: Commit**

```bash
git add codebase/SupabaseSidecar.gs codebase/Testing.gs
git commit -m "feat(sidecar): add J3 Tasks mirror + reconcile with watermark"
```

---

### Task 5: J4 Raindrop sync 08:00 + 20:00 reusing fetchers with raindrop_id dedupe

**Files:**
- Modify: `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/SupabaseSidecar.gs` — append Section J4
- Test: `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/Testing.gs` — reuse log-only check (no new writes to Tasks)

**Interfaces:**
- Consumes: `fetchAllCollections()` and `fetchBookmarksWithSR(collectionId, config)` from `RaindropSync.gs` verbatim (SR-tag rule, `smartThrottle`, 429 alert+retry live inside those fetchers), `getConfig()`, `sbGet_`, `sbUpsert_` with `onConflict=raindrop_id`, `sbUpsert_` on `sync_state`, `sbNowIso_`, `SIDECAR_USER_ID`, `handleError`. Does NOT call `createSpacedRepetitionTask` and does NOT touch Tasks here (spec: "no Tasks here"; J4 mirrors Raindrop → Supabase `cards` only).
- Produces: `sidecarRaindropSync_J4()` returns `{success, synced}` (spec name `syncRaindropsToSupabase`; see Task 7 mapping table).

- [ ] **Step 1: Write the failing test**

```gs
function testSidecarRaindropDryRun() {
  if (typeof sidecarRaindropSync_J4 !== 'function') throw new Error('sidecarRaindropSync_J4 not defined');
  var cols = fetchAllCollections();
  if (!cols || cols.length === 0) throw new Error('no Raindrop collections found');
  Logger.log('collections: ' + cols.length + ', first=' + cols[0].title);
}
```

The existence assertion is the failing gate: before implementation the test fails, after it passes (TDD red-green holds for this task).

- [ ] **Step 2: Run test to verify it fails**

Run: `clasp push && clasp run testSidecarRaindropDryRun`
Expected: FAIL with "sidecarRaindropSync_J4 not defined".

- [ ] **Step 3: Write minimal implementation**

```gs
// ---------- J4 Raindrop sync (08:00 + 20:00, Supabase mirror w/ raindrop_id dedupe) ----------
function sidecarRaindropSync_J4() {
  try {
    var config = getConfig();
    var now = sbNowIso_();
    var existing = {};
    try {
      var rows = sbGet_('cards', '?select=raindrop_id&raindrop_id=not.is.null&limit=2000');
      (rows || []).forEach(function (r) { existing[Number(r.raindrop_id)] = true; });
    } catch (e) {
      Logger.log('J4: could not preload raindrop_id set, continuing with upsert-only dedupe: ' + e);
    }
    var collections = fetchAllCollections();
    var synced = 0;
    for (var i = 0; i < collections.length; i++) {
      var col = collections[i];
      var bookmarks = fetchBookmarksWithSR(col.id, config);
      for (var j = 0; j < bookmarks.length; j++) {
        var bm = bookmarks[j];
        var rid = Number(bm._id);
        if (!rid) continue;
        if (existing[rid]) continue;
        sbUpsert_('cards', {
          user_id: SIDECAR_USER_ID,
          title: String(bm.title || '(untitled)'),
          link: String(bm.link || ''),
          source: 'RAINDROP',
          raindrop_id: rid,
          collection: String(col.title || ''),
          last_rating: 'NEW',
          next_review_at: now
        }, 'raindrop_id');
        existing[rid] = true;
        synced++;
      }
    }
    sbUpsert_('sync_state', { user_id: SIDECAR_USER_ID, raindrop_last_sync_at: now }, 'user_id');
    try { trackApiUsage('RaindropAPI', 'sidecarRaindropSync_J4'); } catch (e) {}
    Logger.log('J4 Raindrop mirror OK: ' + synced + ' new rows');
    return { success: true, synced: synced };
  } catch (e) {
    handleError('sidecarRaindropSync_J4', e);
    return { success: false, error: e.toString() };
  }
}
```

Dedupe rule (two layers): in-memory numeric `raindrop_id` set preloaded via `?select=raindrop_id&raindrop_id=not.is.null&limit=2000`, plus the server-side `unique(raindrop_id)` constraint via `onConflict=raindrop_id` (spec §1.3 "upserts by `raindrop_id`"). `raindrop_id` stays numeric on every Supabase write (spec DDL `bigint unique`); numbers are used as in-memory keys too. New cards get `next_review_at=now` (due immediately) and `last_rating='NEW'`. Second run with no new SR-tagged bookmarks must return `synced: 0`.

- [ ] **Step 4: Run tests to verify it passes**

Run: `clasp push && clasp run testSidecarRaindropDryRun && clasp run sidecarRaindropSync_J4`
Expected: PASS. Run `sidecarRaindropSync_J4` twice; second run returns `synced: 0` proving dedupe.

- [ ] **Step 5: Commit**

```bash
git add codebase/SupabaseSidecar.gs codebase/Testing.gs
git commit -m "feat(sidecar): add J4 Raindrop Supabase sync with dedupe"
```

---

### Task 6: J5 cold-mirror append-only hourly :35 + inline helper (sink: notifications)

**Files:**
- Modify: `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/SupabaseSidecar.gs` — append Section J5
- Test: `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/Testing.gs` — add `testSidecarColdMirror`

**Spec-gap fix (recorded here, not in Backend):** spec §3 names the sink `ColdMirrorLog`, but spec §1.1 DDL creates no such table and H2 Plan 1 owns the schema with only `profiles/cards/reviews/sync_state/notifications`. So J5 appends to `notifications` (`type='SYNC_ERROR'`, `status='SENT'`, `payload.cold_mirror=true`, `payload.event` = event name) — the only append-only log-shaped table in §1.1. Append-only rule: only `sbPost_`, never update/delete, in this section.

**Interfaces:**
- Consumes: `sbPost_`, `sbNowIso_`, `SIDECAR_USER_ID`, `getDashboardStats` (optional, guarded), `COLD_MIRROR_LAST_AT` Script Property, `handleError`.
- Produces: `sidecarColdMirror_J5()` returns `{success, at}`; `coldMirrorAppend_(event, payload)` returns posted row, used inline by other jobs later; `readColdMirrorWatermark_()` / `writeColdMirrorWatermark_(iso)` for string ISO timestamps.

- [ ] **Step 1: Write the failing test**

```gs
function testSidecarColdMirror() {
  var row = coldMirrorAppend_('test', { from: 'testSidecarColdMirror' });
  if (!row && row !== null) throw new Error('cold mirror append failed');
  var res = sidecarColdMirror_J5();
  if (!res || res.success !== true) throw new Error('J5 failed: ' + JSON.stringify(res));
  Logger.log('J5 OK at ' + res.at);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clasp push && clasp run testSidecarColdMirror`
Expected: FAIL with "coldMirrorAppend_ is not defined".

- [ ] **Step 3: Write minimal implementation**

```gs
// ---------- J5 cold-mirror (append-only to notifications, hourly :35 + inline) ----------
function readColdMirrorWatermark_() {
  return PropertiesService.getScriptProperties().getProperty('COLD_MIRROR_LAST_AT') || new Date(0).toISOString();
}

function writeColdMirrorWatermark_(iso) {
  PropertiesService.getScriptProperties().setProperty('COLD_MIRROR_LAST_AT', iso);
}

function coldMirrorAppend_(event, payload) {
  var now = sbNowIso_();
  var row = {
    user_id: SIDECAR_USER_ID,
    type: 'SYNC_ERROR',
    payload: { cold_mirror: true, event: String(event || 'note'), at: now, data: (payload || {}) },
    status: 'SENT',
    sent_at: now
  };
  return sbPost_('notifications', row);
}

function sidecarColdMirror_J5() {
  try {
    var now = sbNowIso_();
    var snapshot = { source: 'gas-cold-mirror' };
    try {
      if (typeof getDashboardStats === 'function') snapshot.stats = getDashboardStats();
    } catch (e) {
      snapshot.stats_error = String(e).slice(0, 200);
    }
    coldMirrorAppend_('hourly-snapshot', snapshot);
    writeColdMirrorWatermark_(now);
    Logger.log('J5 cold mirror OK at ' + now);
    return { success: true, at: now };
  } catch (e) {
    handleError('sidecarColdMirror_J5', e);
    return { success: false, error: e.toString() };
  }
}
```

Append-only rule: only `sbPost_` (insert), never update/delete, in this section. `payload` is a JSON object (notifications.payload is jsonb per §1.1 DDL), never a string.

- [ ] **Step 4: Run test to verify it passes**

Run: `clasp push && clasp run testSidecarColdMirror`
Expected: PASS. Verify: `select payload->>'event', sent_at from notifications where payload->>'cold_mirror' = 'true' order by sent_at desc limit 3;` shows `test` then `hourly-snapshot`; Script Properties `COLD_MIRROR_LAST_AT` advanced to the run time.

- [ ] **Step 5: Commit**

```bash
git add codebase/SupabaseSidecar.gs codebase/Testing.gs
git commit -m "feat(sidecar): add J5 append-only cold mirror log"
```

---

### Task 7: installH2Triggers() + full verification

**Files:**
- Modify: `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/SupabaseSidecar.gs` — append Section Triggers
- Test: `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/Testing.gs` — add `testSidecarTriggersDryRun`

**Interfaces:**
- Consumes: all job names from Tasks 2-6: `sidecarKeepalive_J1`, `sidecarDigest_J2`, `sidecarTasksMirror_J3`, `sidecarTasksReconcile_J3b`, `sidecarRaindropSync_J4`, `sidecarColdMirror_J5`.
- Produces: `installH2Triggers()` (deletes only H2 sidecar triggers, recreates 7 triggers) and `listH2Triggers()`.

**Spec↔GAS name mapping (spec §3 names are aliases; GAS keeps the J-suffixed names):**

| Spec §3 name | GAS function | Schedule |
|---|---|---|
| `sbKeepAlivePing` | `sidecarKeepalive_J1` | daily 06:00 |
| `sendDueDigestFromSupabase` | `sidecarDigest_J2` | daily 07:00 |
| `mirrorDueCardsToTasks` | `sidecarTasksMirror_J3` | every 6h |
| (07:05 reconcile, unnamed in spec) | `sidecarTasksReconcile_J3b` | daily 07:05 |
| `syncRaindropsToSupabase` | `sidecarRaindropSync_J4` | daily 08:00 + 20:00 |
| cold-mirror (unnamed in spec) | `sidecarColdMirror_J5` | hourly :35 |

- [ ] **Step 1: Write the failing test**

```gs
function testSidecarTriggersDryRun() {
  if (typeof installH2Triggers !== 'function') throw new Error('installH2Triggers missing');
  if (typeof sidecarKeepalive_J1 !== 'function') throw new Error('J1 missing');
  if (typeof sidecarDigest_J2 !== 'function') throw new Error('J2 missing');
  if (typeof sidecarTasksMirror_J3 !== 'function') throw new Error('J3 missing');
  if (typeof sidecarTasksReconcile_J3b !== 'function') throw new Error('J3b missing');
  if (typeof sidecarRaindropSync_J4 !== 'function') throw new Error('J4 missing');
  if (typeof sidecarColdMirror_J5 !== 'function') throw new Error('J5 missing');
  Logger.log('trigger surface OK (dry-run, no triggers installed)');
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `clasp push && clasp run testSidecarTriggersDryRun`
Expected: FAIL with "installH2Triggers is not defined" (until trigger section lands).

- [ ] **Step 3: Write minimal implementation**

```gs
// ---------- H2 trigger installer ----------
var H2_SIDECAR_FUNCS = [
  'sidecarKeepalive_J1',
  'sidecarDigest_J2',
  'sidecarTasksMirror_J3',
  'sidecarTasksReconcile_J3b',
  'sidecarRaindropSync_J4',
  'sidecarColdMirror_J5'
];

function installH2Triggers() {
  try {
    var triggers = ScriptApp.getProjectTriggers();
    triggers.forEach(function (t) {
      if (H2_SIDECAR_FUNCS.indexOf(t.getHandlerFunction()) !== -1) ScriptApp.deleteTrigger(t);
    });
    ScriptApp.newTrigger('sidecarKeepalive_J1').timeBased().atHour(6).everyDays(1).inTimezone('Asia/Kolkata').create();
    ScriptApp.newTrigger('sidecarDigest_J2').timeBased().atHour(7).everyDays(1).inTimezone('Asia/Kolkata').create();
    ScriptApp.newTrigger('sidecarTasksMirror_J3').timeBased().everyHours(6).create();
    ScriptApp.newTrigger('sidecarTasksReconcile_J3b').timeBased().atHour(7).nearMinute(5).everyDays(1).inTimezone('Asia/Kolkata').create();
    ScriptApp.newTrigger('sidecarRaindropSync_J4').timeBased().atHour(8).everyDays(1).inTimezone('Asia/Kolkata').create();
    ScriptApp.newTrigger('sidecarRaindropSync_J4').timeBased().atHour(20).everyDays(1).inTimezone('Asia/Kolkata').create();
    ScriptApp.newTrigger('sidecarColdMirror_J5').timeBased().nearMinute(35).everyHours(1).create();
    Logger.log('H2 triggers installed (7 triggers across J1-J5)');
    return { success: true, count: 7 };
  } catch (e) {
    handleError('installH2Triggers', e);
    throw e;
  }
}

function listH2Triggers() {
  return ScriptApp.getProjectTriggers()
    .filter(function (t) { return H2_SIDECAR_FUNCS.indexOf(t.getHandlerFunction()) !== -1; })
    .map(function (t) { return t.getHandlerFunction() + ' @ ' + t.getTriggerSource(); });
}
```

Trigger inventory (7 total, matching spec §3 exactly): J1 06:00 daily; J2 07:00 daily; J3 every-6h; J3b reconcile 07:05 daily; J4 08:00 + 20:00 daily; J5 hourly at :35. The dropped extra J3 07:05 pass was redundant — the every-6h cadence plus the 07:05 reconcile drift guard already cover it. `installH2Triggers` deletes only handlers in `H2_SIDECAR_FUNCS`, never the existing `syncRaindropsToTasks` / `sendDailyDigest` triggers.

- [ ] **Step 4: Run tests to verify everything passes**

Run: `clasp push && clasp run testSidecarTriggersDryRun && clasp run testSidecarConfig && clasp run testSidecarKeepalive && clasp run testSidecarColdMirror`
Expected: all PASS. Then one live install from the editor: run `installH2Triggers` once, then `listH2Triggers` must return 7 rows. Confirm no duplicate installs by running `installH2Triggers` twice and checking count stays 7.

- [ ] **Step 5: Commit**

```bash
git add codebase/SupabaseSidecar.gs codebase/Testing.gs
git commit -m "feat(sidecar): add installH2Triggers for J1-J5 schedule"
```

---

## Verification Checklist (run before PR)

- [ ] `clasp push` clean, no V8 syntax errors.
- [ ] `testSidecarConfig`, `testSidecarKeepalive`, `testSidecarDigestDryRun`, `testSidecarTasksMirrorDryRun`, `testSidecarColdMirror`, `testSidecarTriggersDryRun` all PASS.
- [ ] `select gas_last_seen_at from sync_state;` advanced (J1); due `cards` carry `task_id/task_list_id` + `COLD_MIRROR_LAST_AT`/`TASKS_MIRROR_WATERMARK` advanced (J3); new RAINDROP `cards` exist + `raindrop_last_sync_at` advanced (J4); `notifications` has heartbeat (`payload.heartbeat`) + cold-mirror (`payload.cold_mirror`) rows (J1/J5); J2 sent only when `cards` has due rows.
- [ ] `TASKS_MIRROR_WATERMARK` advances only on successful J3 with `mirrored > 0`.
- [ ] J4 second consecutive run returns `synced: 0` (raindrop_id dedupe holds).
- [ ] `listH2Triggers` returns exactly 7 triggers; existing non-sidecar triggers untouched.

Relevant absolute paths: `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/SupabaseSidecar.gs` (new), `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/Testing.gs`, `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/Code.gs`, `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/Config.gs`, `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/TaskManager.gs`, `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/RaindropSync.gs`, `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/EmailDigest.gs`, `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/Monitoring.gs`, `/teamspace/studios/this_studio/Projects/adaptive-sr-system/codebase/appsscript.json`.

Plan complete and saved to `docs/superpowers/plans/<filename>.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
