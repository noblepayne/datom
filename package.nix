{ pkgs, clj-nix }:

clj-nix.lib.mkCljApp {
  inherit pkgs;
  modules = [{
    projectSrc = ./.;
    name = "datom/datom";
    main-ns = "datom.mcp";
    lockfile = ./deps-lock.json;
    version = "0.1.0";
    java-opts = [
      "-Xmx512m"
      "-XX:+UseSerialGC"
      "-XX:MaxMetaspaceSize=128m"
      "-XX:MaxDirectMemorySize=64m"
      "-Xss512k"
      "-Dorg.bytedeco.javacpp.maxPhysicalBytes=536870912"
    ];
  }];
}