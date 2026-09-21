# Vendored maven artifacts

The Spotube gradle plugin (`dev.krtirtho.spotube:plugin:0.1.1` + its plugin marker)
is only published to KRTirtho's local maven, never to a public repository. It is
vendored here so `nix build .#smplug` stays hermetic. Upstream: github.com/KRTirtho/spotube
(repo: spotube-plugins/plugins), AGPL-3.0-or-later.
