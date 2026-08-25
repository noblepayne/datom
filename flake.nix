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
            # Stage 0: Declarative directory creation with correct ownership
            systemd.tmpfiles.rules = [
              "d /var/lib/datom-backup 0750 ${cfg.user} ${cfg.group} -"
            ];

            systemd.services.datom = {
              description = "Datom composable agent memory server";
              wantedBy = [ "multi-user.target" ];
              after = [ "network.target" ];

              environment = lib.mkForce ({
                DATOM_MCP_PORT = toString cfg.port;
                DATOM_MCP_HOST = cfg.host;
                DATOM_DB_DIR = cfg.dataDir;
                DATOM_SEARCH_DIR = "${cfg.dataDir}/search";
                PATH = "${pkgs.jdk}/bin:${pkgs.coreutils}/bin:${pkgs.gnutar}/bin:${pkgs.gzip}/bin:${pkgs.findutils}/bin:/usr/bin";
                # datalevin writes migration temp dirs to the PARENT of the
                # DB dir; point them somewhere ReadWritePaths covers.
                JAVA_OPTS = "-Ddatalevin.migration.temp.dir=${cfg.dataDir}/tmp";
              } // lib.optionalAttrs (cfg.apiPort != null) {
                DATOM_API_PORT = toString cfg.apiPort;
              });

              serviceConfig = {
                ExecStart = lib.getExe cfg.package;
                # Pre-start hygiene as a real derivation, NOT inline preStart
                # text — inline content collides with ExecStartPre rendering
                # and systemd rejects the unit (exit 203). Runs as root
                # ("+" prefix): clears stale LMDB locks left by OOM-killed
                # JVMs and creates the migration tmp dir with correct owner.
                ExecStartPre = "+${pkgs.writeShellScript "datom-pre-start" ''
                  export PATH="${pkgs.jdk}/bin:${pkgs.coreutils}/bin:${pkgs.gnutar}/bin:${pkgs.gzip}/bin:${pkgs.findutils}/bin:/usr/bin"
                  # Remove stale LMDB lock.mdb only if no datom JVM holds it.
                  # A crashed (OOM-killed) process leaves the lock; it's safe to remove.
                  if [ -f "${cfg.dataDir}/lock.mdb" ]; then
                    if ! pgrep -u ${cfg.user} -f 'datom.*mcp' > /dev/null 2>&1; then
                      rm -f "${cfg.dataDir}/lock.mdb"
                    fi
                  fi
                  # Also clean search/ lock if present
                  if [ -f "${cfg.dataDir}/search/lock.mdb" ]; then
                    if ! pgrep -u ${cfg.user} -f 'datom.*mcp' > /dev/null 2>&1; then
                      rm -f "${cfg.dataDir}/search/lock.mdb"
                    fi
                  fi
                  # Migration temp dir (datalevin targets parent of DB dir)
                  install -d -m 0700 -o ${cfg.user} -g ${cfg.group} "${cfg.dataDir}/tmp"
                ''}";
                User = cfg.user;
                Group = cfg.group;
                StateDirectory = "datom";
                WorkingDirectory = cfg.dataDir;
                Restart = "on-failure";
                RestartSec = "10s";

                # Stage 2a/2c: SIGINT for graceful shutdown, 15s timeout, circuit breaker
                KillSignal = "SIGINT";
                TimeoutStopSec = "15s";
                KillMode = "control-group";
                StartLimitBurst = 3;
                StartLimitIntervalSec = "2min";

                LockPersonality = true;
                NoNewPrivileges = true;
                PrivateDevices = true;
                PrivateTmp = true;
                ProtectHome = true;
                ProtectSystem = "strict";
                # NOTE: no separate ReadWritePaths entry for {dataDir}/tmp —
                # the parent dataDir grant already covers the subtree, and
                # listing a not-yet-existing path here makes systemd fail
                # mount namespacing (226/NAMESPACE) BEFORE ExecStartPre can
                # create it. The pre-start script mkdirs it instead.
                ReadWritePaths = [ cfg.dataDir ];
                EnvironmentPath = "${pkgs.jdk}/bin";
                OOMScoreAdjust = 500;
                MemoryHigh = "700M";
                MemoryMax = "900M";
              };
            };

            # Stage 4: Backup timer (daily + rotation + verification)
            systemd.services.datom-backup = {
              description = "Backup datom data directory";
              wantedBy = [ "multi-user.target" ];
              script = ''
                set -euo pipefail
                export PATH="${pkgs.coreutils}/bin:${pkgs.gnutar}/bin:${pkgs.gzip}/bin:${pkgs.findutils}/bin:/usr/bin:$PATH"
                BACKUP_DIR="/var/lib/datom-backup"
                TODAY="$(date +%Y%m%d)"
                WEEKDAY="$(date +%u)"  # 1=Mon
                BACKUP_NAME="datom-$TODAY"

                if [ -d "${cfg.dataDir}" ]; then
                  cp -al "${cfg.dataDir}" "$BACKUP_DIR/$BACKUP_NAME.tmp" 2>/dev/null \
                    || cp -r "${cfg.dataDir}" "$BACKUP_DIR/$BACKUP_NAME.tmp"
                  rm -f "$BACKUP_DIR/$BACKUP_NAME.tmp/lock.mdb"
                  tar czf "$BACKUP_DIR/$BACKUP_NAME.tar.gz" -C "$BACKUP_DIR" "$BACKUP_NAME.tmp"
                  rm -rf "$BACKUP_DIR/$BACKUP_NAME.tmp"
                  tar tzf "$BACKUP_DIR/$BACKUP_NAME.tar.gz" > /dev/null 2>&1

                  # Rotation: keep 7 daily, 4 weekly (on Sunday)
                  ls -t "$BACKUP_DIR"/datom-*.tar.gz 2>/dev/null | tail -n +8 | xargs -r rm -f
                  if [ "$WEEKDAY" = "7" ]; then
                    ls -t "$BACKUP_DIR"/datom-*.tar.gz 2>/dev/null | tail -n +5 | head -n 4 | xargs -r rm -f
                  fi
                fi
              '';
              serviceConfig = {
                User = cfg.user;
                Group = cfg.group;
                Type = "oneshot";
                ReadWritePaths = [ "/var/lib/datom-backup" ];
                EnvironmentPath = "${pkgs.coreutils}/bin:${pkgs.gnutar}/bin:${pkgs.gzip}/bin:${pkgs.findutils}/bin:/usr/bin";
              };
            };

            systemd.timers.datom-backup = {
              description = "Daily backup for datom";
              wantedBy = [ "timers.target" ];
              timerConfig = {
                OnCalendar = "daily";
                Persistent = true;
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
