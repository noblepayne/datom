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
          hermes-plugin = import ./hermes-plugin.nix { inherit pkgs; lib = pkgs.lib; };
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
      nixosModules.default = { config, lib, pkgs, ... }:
        let
          cfg = config.services.datom;
          pluginPackage = pkgs.callPackage ./hermes-plugin.nix {};
          datom-pkg = self.packages.${pkgs.system}.default;
        in {
          options.services.datom = {
            enable = lib.mkEnableOption "datom composable agent memory server";

            package = lib.mkOption {
              type = lib.types.package;
              description = "Datom server package (built with clj-nix).";
              default = datom-pkg;
            };

            hermesPlugin = lib.mkOption {
              type = lib.types.package;
              description = ''
                Hermes memory provider plugin package.
                Wire into your Hermes agent by setting:
                  HERMES_BUNDLED_PLUGINS = "${pluginPackage}/plugins"
                  memory.provider = "datom"
                in the Hermes config. Also add httpx to the Hermes Python environment
                (services.hermes-agent.extraDependencyGroups or similar).
              '';
              readOnly = true;
              default = pluginPackage;
            };

            port = lib.mkOption {
              type = lib.types.port;
              default = 9090;
              description = "TCP port for the MCP server.";
            };

            apiPort = lib.mkOption {
              type = lib.types.nullOr lib.types.port;
              default = 9091;
              description = ''
                TCP port for the JSON HTTP API, or null to disable.
                The Hermes plugin connects to this port.
              '';
            };

            host = lib.mkOption {
              type = lib.types.str;
              default = "127.0.0.1";
              description = "Listen address.";
            };

            dataDir = lib.mkOption {
              type = lib.types.path;
              default = "/var/lib/datom";
              description = "LMDB data directory (stores both Datalog and search indices).";
            };

            user = lib.mkOption {
              type = lib.types.str;
              default = "datom";
              description = "System user for the datom service.";
            };

            group = lib.mkOption {
              type = lib.types.str;
              default = "datom";
              description = "System group for the datom service.";
            };

            openFirewall = lib.mkOption {
              type = lib.types.bool;
              default = false;
              description = "Open ports in the firewall for datom.";
            };
          };

          config = lib.mkIf cfg.enable {
            systemd.services.datom = {
              description = "Datom composable agent memory server";
              wantedBy = [ "multi-user.target" ];
              after = [ "network.target" ];

              environment = {
                DATOM_MCP_PORT = toString cfg.port;
                DATOM_MCP_HOST = cfg.host;
                DATOM_DB_DIR = cfg.dataDir;
                DATOM_SEARCH_DIR = "${cfg.dataDir}/search";
              } // lib.optionalAttrs (cfg.apiPort != null) {
                DATOM_API_PORT = toString cfg.apiPort;
              };

              serviceConfig = {
                ExecStart = lib.getExe cfg.package;
                User = cfg.user;
                Group = cfg.group;
                StateDirectory = "datom";
                WorkingDirectory = cfg.dataDir;
                Restart = "on-failure";
                RestartSec = "10s";
                LockPersonality = true;
                NoNewPrivileges = true;
                PrivateDevices = true;
                PrivateTmp = true;
                ProtectHome = true;
                ProtectSystem = "strict";
                ReadWritePaths = [ cfg.dataDir ];
              };
            };

            users.users = lib.mkIf (cfg.user == "datom") {
              datom = {
                inherit (cfg) group;
                isSystemUser = true;
                home = cfg.dataDir;
                createHome = true;
              };
            };

            users.groups = lib.mkIf (cfg.group == "datom") {
              datom = {};
            };

            networking.firewall = lib.mkIf cfg.openFirewall {
              allowedTCPPorts = [ cfg.port ] ++ lib.optional (cfg.apiPort != null) cfg.apiPort;
            };
          };
        };
    };
}
