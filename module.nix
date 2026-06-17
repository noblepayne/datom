{ config, pkgs, lib, ... }:

let
  cfg = config.services.datom;
  pluginPackage = pkgs.callPackage ./hermes-plugin.nix {};
in {
  options.services.datom = {
    enable = lib.mkEnableOption "datom composable agent memory server";

    package = lib.mkOption {
      type = lib.types.package;
      description = "Datom server package (built with clj-nix).";
      default = pkgs.callPackage ./package.nix { clj-nix = null; };
    };

    hermesPlugin = lib.mkOption {
      type = lib.types.package;
      description = ''
        Hermes memory provider plugin package.
        Wire into your Hermes agent by setting:
          HERMES_BUNDLED_PLUGINS = "${pluginPackage}/plugins"
          memory.provider = "datom"
        in the Hermes config. Also add httpy to the Hermes Python environment
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

    extraArgs = lib.mkOption {
      type = lib.types.listOf lib.types.str;
      default = [];
      description = "Extra arguments passed to the datom server binary.";
    };

    extraEnvironment = lib.mkOption {
      type = lib.types.attrsOf lib.types.str;
      default = {};
      description = "Extra environment variables for the datom service.";
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

      environment = lib.mkMerge [
        {
          DATOM_MCP_PORT = toString cfg.port;
          DATOM_MCP_HOST = cfg.host;
        }
        (lib.mkIf (cfg.apiPort != null) {
          DATOM_API_PORT = toString cfg.apiPort;
        })
        cfg.extraEnvironment
      ];

      serviceConfig = {
        ExecStart = "${lib.getExe cfg.package} ${toString cfg.port} ${toString cfg.extraArgs}";
        User = cfg.user;
        Group = cfg.group;
        StateDirectory = "datom";
        WorkingDirectory = cfg.dataDir;
        Restart = "on-failure";
        RestartSec = 10;
        LockPersonality = true;
        NoNewPrivileges = true;
        PrivateDevices = true;
        PrivateTmp = true;
        ProtectHome = true;
        ProtectSystem = "strict";
        ReadWritePaths = cfg.dataDir;
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
}