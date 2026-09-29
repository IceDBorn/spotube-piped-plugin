{
  description = "Piped (audio + metadata) Zipline plugin for Spotube";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs =
    {
      self,
      nixpkgs,
    }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "aarch64-darwin"
      ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
      buildScript = pkgs: pkgs.writeShellApplication {
        name = "build-piped-plugin";
        runtimeInputs = [
          pkgs.jdk21
          pkgs.python3
          # GNU realpath, since the BSD one on macOS has no -m.
          pkgs.coreutils
        ]
        ++ pkgs.lib.optional pkgs.stdenv.hostPlatform.isLinux pkgs.iproute2;
        text = ''
          set -euo pipefail
          serve=false
          port=8000
          out="$PWD/spotube-plugin-piped.smplug"
          while [ $# -gt 0 ]; do
            case "$1" in
              --serve)
                serve=true
                if [ $# -gt 1 ] && [[ "$2" =~ ^[0-9]+$ ]]; then
                  port="$2"
                  shift
                fi
                ;;
              *) out="$1" ;;
            esac
            shift
          done
          out=$(realpath -m "$out")

          work=$(mktemp -d)
          cp -r ${self} "$work/plugin"
          chmod -R u+w "$work/plugin"
          cd "$work/plugin"
          ./gradlew --no-daemon :generatePluginJson --rerun-tasks --console=plain
          ./gradlew --no-daemon :packageProductionPlugin --console=plain
          cp build/distributions/plugin-production.smplug "$out"
          echo "built $out"

          if [ "$serve" = true ]; then
            # Serve a directory holding only the bundle, so nothing else next to it is exposed.
            root=$(mktemp -d)
            trap 'rm -rf "$root"' EXIT
            name=$(basename "$out")
            ln -s "$out" "$root/$name"
            echo "serving on port $port (Ctrl+C to stop), install from:"
            echo "  http://localhost:$port/$name"
            if command -v ip >/dev/null; then
              ip -4 -o addr show scope global | while read -r _ _ _ addr _; do
                echo "  http://''${addr%/*}:$port/$name"
              done
            fi
            python3 -m http.server "$port" --directory "$root"
          fi
        '';
      };
    in
    {
      # \`nix run . -- [--serve [port]] [out.smplug]\` builds the plugin with gradle and writes the
      # .smplug into the current directory (default spotube-plugin-piped.smplug).
      # --serve then serves only that file over HTTP (default port 8000) for Spotube's install-from-URL.
      # The build runs as a normal process, so it reaches the network and reuses
      # the caller's ~/.gradle; no derivation or nix store package is involved.
      apps = forAllSystems (pkgs: {
        default = {
          type = "app";
          program = "${buildScript pkgs}/bin/build-piped-plugin";
        };
      });

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell { packages = [ pkgs.jdk21 ]; };
      });
    };
}
