{
  description = "datom — composable agent memory with hybrid search";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    clj-nix.url = "github:jlesquembre/clj-nix";
    clj-nix.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { self, nixpkgs, flake-utils, clj-nix }:
    let
      supportedSystems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = fn: nixpkgs.lib.genAttrs supportedSystems (system: fn system nixpkgs.legacyPackages.${system});

      mkPackage = { system, pkgs }: import ./package.nix { inherit pkgs clj-nix; };
      mkHermesPlugin = { system, pkgs }: import ./hermes-plugin.nix { inherit pkgs; };

      # Scripts available in devShell
      mkScripts = { pkgs, projectDir }: rec {
        test-clojure = pkgs.writeShellScriptBin "test-clojure" ''
          cd ${projectDir}
          rm -rf /tmp/datom-*
          clojure -M:test -d test
        '';
        test-python = pkgs.writeShellScriptBin "test-python" ''
          cd ${projectDir}
          python -m pytest plugins/memory/datom/test_provider.py -v
        '';
        test-all = pkgs.writeShellScriptBin "test-all" ''
          echo "=== Clojure tests ==="
          ${test-clojure}/bin/test-clojure
          echo
          echo "=== Python tests ==="
          ${test-python}/bin/test-python
        '';
        lock = pkgs.writeShellScriptBin "lock" ''
          echo "Regenerating clj-nix deps-lock.json..."
          nix run github:jlesquembre/clj-nix#deps-lock
        '';
        build = pkgs.writeShellScriptBin "build" ''
          nix build . -L
        '';
        run-server = pkgs.writeShellScriptBin "run-server" ''
          clojure -M:mcp "''${1:-9090}"
        '';
      };
    in
    {
      packages = forAllSystems (system: let
        pkgs = nixpkgs.legacyPackages.${system};
      in {
        default = mkPackage { inherit system pkgs; };
        hermes-plugin = mkHermesPlugin { inherit system pkgs; };

        # Convenience alias for generating clj-nix lockfile
        deps-lock = clj-nix.packages.${system}.deps-lock;
      });

      nixosModules.default = import ./module.nix;

      devShells = forAllSystems (system: let
        pkgs = nixpkgs.legacyPackages.${system};
        scripts = mkScripts { inherit pkgs; projectDir = ./.; };
      in {
        default = pkgs.mkShell {
          name = "datom-dev-shell";
          packages = with pkgs; [
            jdk
            clojure
            clj-kondo
            cljfmt
            (python3.withPackages (ps: with ps; [ httpx pytest ]))
            scripts.test-clojure
            scripts.test-python
            scripts.test-all
            scripts.lock
            scripts.build
            scripts.run-server
          ];
          shellHook = ''
            echo "datom dev shell"
            echo "  test-clojure    — run Clojure tests"
            echo "  test-python     — run Python plugin tests"
            echo "  test-all        — run both"
            echo "  lock            — regenerate clj-nix deps-lock.json"
            echo "  build           — nix build ."
            echo "  run-server [p]  — start datom MCP server on port [default: 9090]"
          '';
        };
      });

      formatter = forAllSystems (system: nixpkgs.legacyPackages.${system}.nixfmt-rfc-style);
    };
}