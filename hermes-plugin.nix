{ pkgs, lib }:

# Produces a derivation containing plugins/memory/datom/ for Hermes discovery.
# Point HERMES_BUNDLED_PLUGINS to $out/plugins and Hermes finds the provider.
# Also provides a Python package with httpy dependency for the plugin.
let
  python = pkgs.python3;
  pythonEnv = python.withPackages (ps: [ ps.httpx ]);

  pluginSrc = ./plugins/memory/datom;
in
pkgs.stdenv.mkDerivation {
  pname = "hermes-datom-plugin";
  version = "0.1.0";

  src = pluginSrc;

  dontBuild = true;

  installPhase = ''
    mkdir -p $out/plugins/memory/datom
    cp -r $src/* $out/plugins/memory/datom/
    chmod -R +w $out/plugins/memory/datom/
  '';

  passthru.pythonEnv = pythonEnv;

  meta = {
    description = "Datom memory provider plugin for Hermes Agent";
    homepage = "https://github.com/noblepayne/datom";
    license = lib.licenses.epl20;
  };
}