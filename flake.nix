{
  description = "Piped (audio + metadata) Zipline plugin for Spotube";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs =
    {
      self,
      nixpkgs,
    }:
    let
      system = "x86_64-linux";
      pkgs = nixpkgs.legacyPackages.${system};
      buildScript = pkgs.writeShellApplication {
        name = "build-piped-plugin";
        runtimeInputs = [ pkgs.jdk ];
        text = ''
          set -euo pipefail
          work=$(mktemp -d)
          cp -r ${self} "$work/plugin"
          chmod -R u+w "$work/plugin"
          out="''${1:-$PWD/spotube-plugin-piped.smplug}"
          cd "$work/plugin"
          ./gradlew --no-daemon :generatePluginJson --rerun-tasks --console=plain
          ./gradlew --no-daemon :packageProductionPlugin --console=plain
          cp build/distributions/plugin-production.smplug "$out"
          echo "built $out"
        '';
      };
    in
    {
      # \`nix run . -- [out.smplug]\` builds the plugin with gradle and writes the
      # .smplug into the current directory (default spotube-plugin-piped.smplug).
      # The build runs as a normal process, so it reaches the network and reuses
      # the caller's ~/.gradle; no derivation or nix store package is involved.
      apps.${system}.default = {
        type = "app";
        program = "${buildScript}/bin/build-piped-plugin";
      };

      devShells.${system}.default = pkgs.mkShell { packages = [ pkgs.jdk ]; };
    };
}
