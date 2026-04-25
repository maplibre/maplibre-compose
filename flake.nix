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
          ];
        in
        {
          default = (pkgs.mkShell.override { stdenv = pkgs.clangStdenv; }) {
            packages = with pkgs; [
              bzip2
              cmake
              curl
              icu
              libGL
              libjpeg_turbo
              libpng
              libuv
              libwebp
              ninja
              pkg-config
              vulkan-headers
              vulkan-loader
              libx11
            ];

            LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath runtimeLibraries;
          };
        });
    };
}
