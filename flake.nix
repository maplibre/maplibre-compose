{
  description = "Development shell for MapLibre Compose";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      linuxSystems = [
        "x86_64-linux"
        "aarch64-linux"
      ];

      forEachLinuxSystem = nixpkgs.lib.genAttrs linuxSystems;
    in
    {
      devShells = forEachLinuxSystem (system:
        let
          pkgs = import nixpkgs { inherit system; };
          runtimeLibraries = with pkgs; [
            fontconfig
            freetype
            libGL
            stdenv.cc.cc.lib
            libxkbcommon
            wayland
            libx11
            libxcursor
            libxext
            libxi
            libxrandr
            libxrender
            libxtst
            # MapLibre Native FFI renders through Vulkan on Linux and dlopens
            # libvulkan.so.1 at runtime, so the loader has to be on
            # LD_LIBRARY_PATH rather than just in `packages`.
            vulkan-loader
          ];
        in
        {
          default = pkgs.mkShell {
            LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath runtimeLibraries;
          };
        });
    };
}
