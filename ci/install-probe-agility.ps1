# Investigation only: give dzn a pinned Agility SDK instead of the runner's system D3D12Core.
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$version = "1.619.6"
$archive = Join-Path $env:RUNNER_TEMP "agility-$version.zip"
Invoke-WebRequest "https://api.nuget.org/v3-flatcontainer/microsoft.direct3d.d3d12/$version/microsoft.direct3d.d3d12.$version.nupkg" -OutFile $archive
$expected = "08F0489281401AA430FC37322D6C3FC98A8025175AACD714C10D562F4963F1E9"
if ((Get-FileHash $archive -Algorithm SHA256).Hash -ne $expected) {
    throw "Agility SDK archive hash mismatch"
}
$root = Join-Path $env:RUNNER_TEMP "agility-$version"
Expand-Archive $archive $root
$driverRoot = Split-Path $env:VK_DRIVER_FILES
# dzn's try_create_device_factory looks beside vulkan_dzn.dll for this SDK.
Copy-Item "$root/build/native/bin/x64/*.dll" $driverRoot
Get-Item "$driverRoot/D3D12Core.dll" |
    ForEach-Object { Write-Output "dzn Agility SDK: $($_.VersionInfo.FileVersion)" }
