package dev.icedborn.spotube_plugin_piped_metadata

/** Settings page shown in the plugin webview; posts JSON over the host bridge. */
internal fun settingsFormHtml(instance: String, playback: String, username: String): String = FORM_HTML
    .replace("__INSTANCE__", instance)
    .replace("__PLAYBACK__", playback)
    .replace("__USERNAME__", username)

private val FORM_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Piped Metadata</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; padding: 32px; background: #101014; color: #e8e8ea;
         font-family: system-ui, -apple-system, sans-serif; }
  form { max-width: 560px; margin: 0 auto; }
  h1 { font-size: 20px; margin: 0 0 6px; }
  p.lead { color: #a0a0aa; font-size: 13px; line-height: 1.5; margin: 0 0 22px; }
  label { display: block; font-size: 12px; letter-spacing: .04em; text-transform: uppercase;
          color: #a0a0aa; margin: 16px 0 6px; }
  input[type=text], input[type=password] { width: 100%; box-sizing: border-box; padding: 10px 12px;
          font-size: 14px; color: #e8e8ea; background: #1b1b21; border: 1px solid #34343f;
          border-radius: 8px; }
  input:focus { outline: none; border-color: #6c5ce7; }
  .check { display: flex; align-items: center; gap: 10px; margin-top: 16px; font-size: 14px;
           text-transform: none; letter-spacing: normal; }
  .row { display: flex; gap: 16px; align-items: center; margin-top: 26px; }
  button { padding: 10px 18px; font-size: 14px; border-radius: 8px; border: 0; cursor: pointer; }
  #save { background: #6c5ce7; color: #ffffff; }
  #save:disabled { opacity: .55; cursor: default; }
  #status { margin-top: 18px; font-size: 13px; min-height: 18px; color: #a0a0aa; }
  #status.error { color: #ff7676; }
</style>
</head>
<body>
<form id="settings" autocomplete="off">
  <h1>Piped Metadata</h1>
  <p class="lead">Choose the Piped instance you want to use and save its URL below.
  There is no default instance. Public instances are listed in the
  <a href="https://github.com/TeamPiped/Piped/wiki/Instances" target="_blank">TeamPiped wiki</a>.
  Optional: a separate playback instance, used only to resolve audio, with your
  main instance handling search, metadata and your account.</p>

  <label for="instance">Piped instance (required)</label>
  <input id="instance" type="text" value="__INSTANCE__" placeholder="https://pipedapi.kavin.rocks" spellcheck="false">

  <label for="playback">Playback instance (optional)</label>
  <input id="playback" type="text" value="__PLAYBACK__" placeholder="leave blank to use the main instance" spellcheck="false">

  <label for="username">Username</label>
  <input id="username" type="text" value="__USERNAME__" spellcheck="false">

  <label for="password">Password</label>
  <input id="password" type="password" placeholder="your account password" spellcheck="false">

  <label class="check"><input id="createAccount" type="checkbox" checked>
    Create the account if it does not exist (instances may disable registration)</label>

  <div class="row">
    <button id="save" type="submit">Sign in</button>
    <button id="saveInstance" type="button">Save instance only</button>
  </div>
  <p id="status"></p>
</form>
<script>
  function el(id) { return document.getElementById(id); }
  function send(message) {
    if (typeof window.sendMessage === 'function') { window.sendMessage(message); return true; }
    if (window.kmpJsBridge && typeof window.kmpJsBridge.callNative === 'function') {
      window.kmpJsBridge.callNative('sendMessage', message); return true;
    }
    return false;
  }
  function disableButtons(value) {
    el('save').disabled = value;
    el('saveInstance').disabled = value;
  }
  function post(payload, busyText) {
    if (!send(JSON.stringify(payload))) {
      var box = el('status');
      box.className = 'error';
      box.textContent = 'The app bridge is not ready yet. Wait a moment, then press the button again.';
      disableButtons(false);
      return;
    }
    disableButtons(true);
    el('status').className = '';
    el('status').textContent = busyText;
  }
  el('settings').addEventListener('submit', function (e) {
    e.preventDefault();
    post({
      action: 'login',
      instance: el('instance').value.trim(),
      playback: el('playback').value.trim(),
      username: el('username').value.trim(),
      password: el('password').value,
      createAccount: el('createAccount').checked
    }, 'Signing in...');
  });
  el('saveInstance').addEventListener('click', function () {
    post({ action: 'instance', instance: el('instance').value.trim(), playback: el('playback').value.trim() }, 'Saving instance...');
  });
</script>
"""
