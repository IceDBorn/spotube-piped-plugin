package dev.icedborn.spotube_plugin_piped_metadata

/** Settings page shown in the plugin webview; posts JSON over the host bridge.
 * [manage] opens the same form from the host's logout button: signed in, no sign-in fields. */
internal fun settingsFormHtml(
    instance: String,
    playback: String,
    username: String,
    lightTheme: Boolean,
    region: String,
    detectedRegion: String?,
    channel: String,
    library: String = LibraryPlaylist.ALWAYS.name,
    nonce: String = "",
    manage: Boolean = false,
    signedInAs: String? = null,
    signedInOn: String? = null,
): String {
    val values = mapOf(
        "THEME" to if (lightTheme) "light" else "dark",
        "INSTANCE" to escapeAttribute(instance),
        "PLAYBACK" to escapeAttribute(playback),
        "USERNAME" to escapeAttribute(username),
        "REGIONS" to regionOptions(region, detectedRegion),
        "CHANNELS" to channelOptions(channel),
        "LIBRARY" to libraryOptions(library),
        "NONCE" to escapeAttribute(nonce),
        "MANAGE" to if (manage) "true" else "false",
        "SIGNEDIN" to signedInLine(signedInAs, signedInOn),
    )
    // One pass, so a value that contains a placeholder name is never substituted again.
    return PLACEHOLDER.replace(FORM_HTML) { values[it.groupValues[1]] ?: it.value }
}

private fun signedInLine(username: String?, instance: String?): String {
    if (username.isNullOrBlank() || instance.isNullOrBlank()) return ""
    return "Signed in as <strong>${escapeHtml(username)}</strong> on ${escapeHtml(instance)}"
}

private fun libraryOptions(selected: String): String = listOf(
    LibraryPlaylist.ALWAYS to "Always show Recently played",
    LibraryPlaylist.WHEN_EMPTY to "Only when there are no playlists",
    LibraryPlaylist.OFF to "Off",
).joinToString("") { (mode, label) ->
    val mark = if (mode.name == selected) " selected" else ""
    "<option value=\"${mode.name}\"$mark>$label</option>"
}

private val PLACEHOLDER = Regex("__([A-Z]+)__")

private fun regionOptions(selected: String, detected: String?): String {
    val auto = "Auto (" + (detected?.let { CHART_COUNTRIES[it] } ?: "Global") + ")"
    val options = listOf(REGION_AUTO to auto, REGION_GLOBAL to "Global") +
        CHART_COUNTRIES.entries.sortedBy { it.value }.map { it.key to it.value }
    return options.joinToString("") { (code, name) ->
        val mark = if (code == selected) " selected" else ""
        "<option value=\"$code\"$mark>${escapeAttribute(name)}</option>"
    }
}

private fun channelOptions(selected: String): String =
    listOf(
        UpdateChannel.AUTO to "Auto",
        UpdateChannel.STABLE to "Stable",
        UpdateChannel.NIGHTLY to "Nightly",
    ).joinToString("") { (channel, label) ->
        val mark = if (channel.name == selected) " selected" else ""
        "<option value=\"${channel.name}\"$mark>$label</option>"
    }

private fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private fun escapeAttribute(value: String): String = value
    .replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private val FORM_HTML = """<!doctype html>
<html lang="en" data-theme="__THEME__" data-manage="__MANAGE__">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Piped</title>
<style>
  /* Piped logo red on neutral greys. Webviews do not report Spotube's theme, so the page has its own toggle */
  :root { color-scheme: dark; --bg: #111111; --fg: #ececec; --muted: #a3a3a3; --field: #1c1c1c;
          --border: #353535; --accent: #d03125; --on-accent: #ffffff; --secondary: #2a2a2a;
          --link: #ff8a7a; --error: #ff8a80; --notice: #2a1614; }
  :root[data-theme=light] { color-scheme: light; --bg: #ffffff; --fg: #1a1a1a; --muted: #5f5f5f; --field: #ffffff; --border: #d0d0d0;
            --secondary: #ececec; --link: #b8221c; --error: #b3261e; --notice: #fdecea; }
  /* Linux Nucleus webview forces a white background-color via user !important; an image still paints */
  html, body { background: var(--bg) linear-gradient(var(--bg), var(--bg)); }
  body { margin: 0; padding: 32px; color: var(--fg);
         font-family: system-ui, -apple-system, sans-serif; }
  [hidden] { display: none !important; }
  a { color: var(--link); }
  main { max-width: 560px; margin: 0 auto; }
  header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 0 0 6px; }
  h1 { font-size: 20px; margin: 0; }
  p.lead { color: var(--muted); font-size: 13px; line-height: 1.5; margin: 0 0 18px; }
  .tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--border); margin-bottom: 18px; }
  .tab { background: none; color: var(--muted); border-radius: 0; padding: 10px 14px;
         border-bottom: 2px solid transparent; margin-bottom: -1px; }
  .tab[aria-selected=true] { color: var(--fg); border-bottom-color: var(--accent); }
  .notice { background: var(--notice); border: 1px solid var(--accent); border-radius: 8px;
            padding: 12px 14px; font-size: 13px; line-height: 1.5; margin-bottom: 6px; }
  .target { font-size: 13px; color: var(--muted); margin: 0 0 6px; overflow-wrap: anywhere; }
  .target strong { color: var(--fg); font-weight: 600; }
  label { display: block; font-size: 12px; letter-spacing: .04em; text-transform: uppercase;
          color: var(--muted); margin: 16px 0 6px; }
  input[type=text], input[type=password] { width: 100%; box-sizing: border-box; padding: 10px 12px;
          font-size: 14px; color: var(--fg); background: var(--field); border: 1px solid var(--border);
          border-radius: 8px; }
  input::placeholder { color: var(--muted); }
  select { width: 100%; box-sizing: border-box; padding: 10px 12px; font-size: 14px; color: var(--fg);
           background: var(--field); border: 1px solid var(--border); border-radius: 8px; font-family: inherit; }
  .hint { color: var(--muted); font-size: 12px; line-height: 1.5; margin: 6px 0 0; }
  input:focus, select:focus { outline: none; border-color: var(--accent); }
  input[type=checkbox] { accent-color: var(--accent); }
  .check { display: flex; align-items: center; gap: 10px; margin-top: 16px; font-size: 14px;
           color: var(--fg); text-transform: none; letter-spacing: normal; }
  .row { display: flex; flex-wrap: wrap; gap: 12px 16px; align-items: center; margin-top: 26px; }
  button { padding: 10px 18px; font-size: 14px; border-radius: 8px; border: 0; cursor: pointer;
           font-family: inherit; }
  .primary { background: var(--accent); color: var(--on-accent); }
  .secondary { background: var(--secondary); color: var(--fg); }
  button:disabled { opacity: .55; cursor: default; }
  #status { margin-top: 18px; font-size: 13px; min-height: 18px; color: var(--muted); }
  #status.error { color: var(--error); }
</style>
</head>
<body>
<main>
  <header>
    <h1>Piped</h1>
    <button id="theme" class="secondary" type="button"></button>
  </header>
  <p class="lead">Search, metadata and audio come from a Piped instance. An account on that
  instance is optional and syncs saved albums, artists and favorites to it.</p>

  <div class="tabs" role="tablist">
    <button id="tabLogin" class="tab" type="button" role="tab">Login</button>
    <button id="tabInstance" class="tab" type="button" role="tab">Instance</button>
    <button id="tabSettings" class="tab" type="button" role="tab">Settings</button>
  </div>

  <form id="paneLogin" autocomplete="off">
    <div id="noInstance" class="notice" hidden>No Piped instance is set up. The plugin ships without a
      default instance, so choose one in <a href="#" id="goInstance">Instance</a> before signing in.</div>
    <p id="loginTarget" class="target" hidden>Signing in to <strong id="currentInstance"></strong></p>
    <p id="signedIn" class="target" hidden>__SIGNEDIN__</p>

    <div id="signInFields">
      <label for="username">Username</label>
      <input id="username" type="text" value="__USERNAME__" spellcheck="false">

      <label for="password">Password</label>
      <input id="password" type="password" placeholder="your account password" spellcheck="false">

      <label class="check"><input id="createAccount" type="checkbox" checked>
        Create the account if it does not exist (instances may disable registration)</label>
    </div>

    <div class="row">
      <button id="login" class="primary action" type="submit">Sign in</button>
      <button id="skip" class="secondary action" type="button">Continue without account</button>
      <button id="logOut" class="secondary action" type="button">Log out</button>
      <button id="close" class="secondary action" type="button">Done</button>
    </div>
  </form>

  <form id="paneInstance" autocomplete="off" hidden>
    <p class="lead">Public instances are listed in the
    <a href="https://github.com/TeamPiped/Piped/wiki/Instances" target="_blank">TeamPiped wiki</a>.
    The playback instance is optional and only resolves audio. The main instance handles search,
    metadata and your account.</p>

    <label for="instance">Piped instance (required)</label>
    <input id="instance" type="text" value="__INSTANCE__" placeholder="https://pipedapi.kavin.rocks" spellcheck="false">

    <label for="playback">Playback instance (optional)</label>
    <input id="playback" type="text" value="__PLAYBACK__" placeholder="leave blank to use the main instance" spellcheck="false">

    <div class="row">
      <button id="saveInstance" class="primary action" type="submit">Save instance</button>
      <button id="closeInstance" class="secondary action" type="button">Done</button>
    </div>
  </form>

  <form id="paneSettings" autocomplete="off" hidden>
    <label for="region">Charts region</label>
    <select id="region">__REGIONS__</select>
    <p class="hint">Picks the YouTube Music charts on Home. Auto follows the system time zone, and countries
    without charts use Global. Spotube keeps Home until it restarts, so a change shows after a restart.</p>

    <label for="channel">Update channel</label>
    <select id="channel">__CHANNELS__</select>
    <p class="hint">Picks which GitHub release the update check offers. Auto follows the installed build, so a
    nightly install stays on nightlies. After switching from Nightly to Stable, no update is offered until a
    stable release is newer than the installed nightly. To go back sooner, reinstall a stable build from the
    Releases page.</p>

    <label for="library">Library playlist</label>
    <select id="library">__LIBRARY__</select>
    <p class="hint">Spotube only shows its Liked Tracks card when the list has at least one playlist, so the
    plugin adds a generated one. Recently played lists the last 50 tracks; with no history yet it shows your
    saved tracks instead. Off removes it, and with no other playlist the Liked Tracks card disappears too.</p>

    <div class="row">
      <button id="closeSettings" class="secondary action" type="button">Done</button>
    </div>
  </form>

  <p id="status"></p>
</main>
<script>
  function el(id) { return document.getElementById(id); }
  var savedInstance = el('instance').defaultValue.trim();
  var manage = document.documentElement.dataset.manage === 'true';
  var signedIn = el('signedIn').textContent.length > 0;
  // The host replays the last message of a previous form to a new subscriber; the nonce drops it.
  var nonce = '__NONCE__';
  function send(message) {
    if (typeof window.sendMessage === 'function') { window.sendMessage(message); return true; }
    if (window.kmpJsBridge && typeof window.kmpJsBridge.callNative === 'function') {
      window.kmpJsBridge.callNative('sendMessage', message); return true;
    }
    return false;
  }
  function setStatus(text, isError) {
    el('status').className = isError ? 'error' : '';
    el('status').textContent = text;
  }
  function refreshLogin() {
    var hasInstance = savedInstance.length > 0;
    el('noInstance').hidden = hasInstance;
    el('loginTarget').hidden = !hasInstance || signedIn || manage;
    el('currentInstance').textContent = savedInstance;
    // A session shows on both forms; the sign-in fields only on the login form, Log out only on the manage form.
    el('signedIn').hidden = !signedIn;
    el('signInFields').hidden = manage;
    el('login').hidden = manage;
    el('skip').hidden = !hasInstance || manage;
    el('logOut').hidden = !manage;
    el('login').disabled = !hasInstance;
  }
  // The host re-enables buttons through this after an error status.
  window.disableButtons = function (value) {
    document.querySelectorAll('button.action').forEach(function (b) { b.disabled = value; });
    if (!value) refreshLogin();
  };
  // The host calls this once it has stored the instance.
  window.onInstanceSaved = function (url, text, sessionKept) {
    savedInstance = url;
    // Saving another instance drops the session, so the Login tab must stop claiming one.
    if (!sessionKept) {
      signedIn = false;
      el('signedIn').textContent = '';
    }
    window.disableButtons(false);
    setStatus(text, false);
    showTab(manage ? 'settings' : 'login');
  };
  function showTab(name) {
    ['login', 'instance', 'settings'].forEach(function (tab) {
      var on = tab === name;
      el('pane' + tab[0].toUpperCase() + tab.slice(1)).hidden = !on;
      el('tab' + tab[0].toUpperCase() + tab.slice(1)).setAttribute('aria-selected', String(on));
    });
  }
  // press is false for a change that saves itself, so no button is left disabled.
  function post(payload, busyText, press) {
    payload.nonce = nonce;
    if (!send(JSON.stringify(payload))) {
      setStatus('The app bridge is not ready yet. Wait a moment, then press the button again.', true);
      return false;
    }
    if (press !== false) window.disableButtons(true);
    if (busyText) setStatus(busyText, false);
    return true;
  }
  function refreshTheme() {
    var light = document.documentElement.dataset.theme === 'light';
    el('theme').textContent = light ? 'Dark theme' : 'Light theme';
  }
  el('theme').addEventListener('click', function () {
    var light = document.documentElement.dataset.theme !== 'light';
    document.documentElement.dataset.theme = light ? 'light' : 'dark';
    refreshTheme();
    post({ action: 'theme', light: light }, '', false);
  });
  el('channel').addEventListener('change', function () {
    post({ action: 'channel', channel: el('channel').value }, 'Saved.', false);
  });
  el('region').addEventListener('change', function () {
    post({ action: 'region', region: el('region').value }, 'Saved. Restart Spotube to refresh Home.', false);
  });
  el('library').addEventListener('change', function () {
    post({ action: 'library', library: el('library').value }, 'Saved.', false);
  });
  el('tabLogin').addEventListener('click', function () { showTab('login'); });
  el('tabInstance').addEventListener('click', function () { showTab('instance'); });
  el('tabSettings').addEventListener('click', function () { showTab('settings'); });
  el('goInstance').addEventListener('click', function (e) { e.preventDefault(); showTab('instance'); });
  el('paneLogin').addEventListener('submit', function (e) {
    e.preventDefault();
    post({
      action: 'login',
      username: el('username').value.trim(),
      password: el('password').value,
      createAccount: el('createAccount').checked
    }, 'Signing in...');
  });
  el('skip').addEventListener('click', function () { post({ action: 'skip' }, 'Closing...'); });
  el('logOut').addEventListener('click', function () { post({ action: 'logout' }, 'Logging out...'); });
  el('close').addEventListener('click', function () { post({ action: 'close' }, 'Closing...'); });
  el('closeInstance').addEventListener('click', function () { post({ action: 'close' }, 'Closing...'); });
  el('closeSettings').addEventListener('click', function () { post({ action: 'close' }, 'Closing...'); });
  el('paneInstance').addEventListener('submit', function (e) {
    e.preventDefault();
    post({ action: 'instance', instance: el('instance').value.trim(), playback: el('playback').value.trim() },
      'Saving instance...');
  });
  refreshTheme();
  refreshLogin();
  // Signing in is impossible without an instance, so that tab opens first.
  showTab(manage ? 'settings' : (savedInstance ? 'login' : 'instance'));
</script>
"""
