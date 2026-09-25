package dev.icedborn.spotube_plugin_piped_metadata

/** Settings page shown in the plugin webview; posts JSON over the host bridge. */
internal fun settingsFormHtml(instance: String, playback: String, username: String, lightTheme: Boolean): String {
    val values = mapOf(
        "THEME" to if (lightTheme) "light" else "dark",
        "INSTANCE" to escapeAttribute(instance),
        "PLAYBACK" to escapeAttribute(playback),
        "USERNAME" to escapeAttribute(username),
    )
    // One pass, so a value that contains a placeholder name is never substituted again.
    return PLACEHOLDER.replace(FORM_HTML) { values[it.groupValues[1]] ?: it.value }
}

private val PLACEHOLDER = Regex("__([A-Z]+)__")

private fun escapeAttribute(value: String): String = value
    .replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private val FORM_HTML = """<!doctype html>
<html lang="en" data-theme="__THEME__">
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
  input:focus { outline: none; border-color: var(--accent); }
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
    <button id="tabLogin" class="tab" type="button" role="tab">Sign in</button>
    <button id="tabInstance" class="tab" type="button" role="tab">Instance</button>
  </div>

  <form id="paneLogin" autocomplete="off">
    <div id="noInstance" class="notice" hidden>No Piped instance is set up. The plugin ships without a
      default instance, so choose one in <a href="#" id="goInstance">Instance</a> before signing in.</div>
    <p id="loginTarget" class="target" hidden>Signing in to <strong id="currentInstance"></strong></p>

    <label for="username">Username</label>
    <input id="username" type="text" value="__USERNAME__" spellcheck="false">

    <label for="password">Password</label>
    <input id="password" type="password" placeholder="your account password" spellcheck="false">

    <label class="check"><input id="createAccount" type="checkbox" checked>
      Create the account if it does not exist (instances may disable registration)</label>

    <div class="row">
      <button id="login" class="primary action" type="submit">Sign in</button>
      <button id="skip" class="secondary action" type="button">Continue without account</button>
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
    </div>
  </form>

  <p id="status"></p>
</main>
<script>
  function el(id) { return document.getElementById(id); }
  var savedInstance = el('instance').defaultValue.trim();
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
    el('loginTarget').hidden = !hasInstance;
    el('currentInstance').textContent = savedInstance;
    el('login').disabled = !hasInstance;
    el('skip').hidden = !hasInstance;
  }
  // The host re-enables buttons through this after an error status.
  window.disableButtons = function (value) {
    document.querySelectorAll('button.action').forEach(function (b) { b.disabled = value; });
    if (!value) refreshLogin();
  };
  // The host calls this once it has stored the instance.
  window.onInstanceSaved = function (url, text) {
    savedInstance = url;
    window.disableButtons(false);
    setStatus(text, false);
    showTab('login');
  };
  function showTab(name) {
    var login = name === 'login';
    el('paneLogin').hidden = !login;
    el('paneInstance').hidden = login;
    el('tabLogin').setAttribute('aria-selected', String(login));
    el('tabInstance').setAttribute('aria-selected', String(!login));
  }
  function post(payload, busyText) {
    if (!send(JSON.stringify(payload))) {
      setStatus('The app bridge is not ready yet. Wait a moment, then press the button again.', true);
      return;
    }
    window.disableButtons(true);
    setStatus(busyText, false);
  }
  function refreshTheme() {
    var light = document.documentElement.dataset.theme === 'light';
    el('theme').textContent = light ? 'Dark theme' : 'Light theme';
  }
  el('theme').addEventListener('click', function () {
    var light = document.documentElement.dataset.theme !== 'light';
    document.documentElement.dataset.theme = light ? 'light' : 'dark';
    refreshTheme();
    send(JSON.stringify({ action: 'theme', light: light }));
  });
  el('tabLogin').addEventListener('click', function () { showTab('login'); });
  el('tabInstance').addEventListener('click', function () { showTab('instance'); });
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
  el('paneInstance').addEventListener('submit', function (e) {
    e.preventDefault();
    post({ action: 'instance', instance: el('instance').value.trim(), playback: el('playback').value.trim() },
      'Saving instance...');
  });
  refreshTheme();
  refreshLogin();
  showTab(savedInstance ? 'login' : 'instance');
</script>
"""
