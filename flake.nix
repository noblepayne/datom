{
  description = "datom — composable agent memory with hybrid search";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    clj-nix.url = "github:jlesquembre/clj-nix";
    clj-nix.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { self, nixpkgs, flake-utils, clj-nix }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

        # --- Helpers ---
        scripts = rec {
          test-clojure = pkgs.writeShellScriptBin "test-clojure" ''
            cd ${toString ./.}
            rm -rf /tmp/datom-*
            clojure -M:test -d test
          '';
          test-python = pkgs.writeShellScriptBin "test-python" ''
            cd ${toString ./.}
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
            cd ${toString ./.}
            clojure -M:mcp "''${1:-9090}"
          '';
        };
      in
      {
        packages = {
          default = import ./package.nix { inherit pkgs clj-nix; };
          hermes-plugin = import ./hermes-plugin.nix { inherit pkgs; };
          deps-lock = clj-nix.packages.${system}.deps-lock;
        };

        devShells.default = pkgs.mkShell {
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
            echo "  test-clojure    — run Clojure tests (16 tests, 91 assertions)"
            echo "  test-python     — run Python plugin tests (35 tests)"
            echo "  test-all        — run both"
            echo "  lock            — regenerate clj-nix deps-lock.json"
            echo "  build           — nix build ."
            echo "  run-server [p]  — start datom MCP server on port [default: 9090]"
          '';
        };

        formatter = pkgs.nixfmt-rfc-style;
      }
    ) // {
      nixosModules.default = import ./module.nix;
    };
}