{ pkgs, clj-nix }:

clj-nix.lib.mkCljApp {
  inherit pkgs;
  modules = [{
    projectSrc = ./.;
    name = "datom/datom";
    main-ns = "datom.mcp";
    lockfile = ./deps-lock.json;
    version = "0.1.0";
    java-opts = [ "-Xmx512m" "-XX:+UseSerialGC" ];
  }];
}