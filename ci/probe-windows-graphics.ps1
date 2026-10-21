# Investigation only: distinguish missing displays from unavailable graphics APIs in session 0.
$ErrorActionPreference = "Stop"

Get-Process -Id $PID | Select-Object ProcessName, SessionId
Get-CimInstance Win32_VideoController |
    Select-Object Name, DriverVersion, Status, VideoModeDescription

Get-Item "$env:WINDIR/System32/d3d12.dll", "$env:WINDIR/System32/D3D12Core.dll", "$env:WINDIR/System32/d3d10warp.dll" |
    ForEach-Object { Write-Output "$($_.Name): $($_.VersionInfo.FileVersion)" }

Add-Type @'
using System;
using System.Runtime.InteropServices;

public static class WindowsGraphicsProbe {
    [DllImport("dxgi.dll", ExactSpelling = true)]
    static extern int CreateDXGIFactory1(ref Guid iid, out IntPtr factory);

    [DllImport("dxcore.dll", ExactSpelling = true)]
    static extern int DXCoreCreateAdapterFactory(ref Guid iid, out IntPtr factory);

    [DllImport("d3d12.dll", ExactSpelling = true)]
    static extern int D3D12CreateDevice(IntPtr adapter, int minimumFeatureLevel,
        ref Guid iid, out IntPtr device);

    [DllImport("d3d12.dll", ExactSpelling = true)]
    static extern int D3D12GetInterface(ref Guid clsid, ref Guid iid, out IntPtr factory);

    [DllImport("d3d11.dll", ExactSpelling = true)]
    static extern int D3D11CreateDevice(IntPtr adapter, int driverType, IntPtr software,
        uint flags, IntPtr featureLevels, uint featureLevelCount, uint sdkVersion,
        out IntPtr device, out int featureLevel, out IntPtr context);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    delegate int EnumWarpAdapter(IntPtr factory, ref Guid iid, out IntPtr adapter);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    delegate int FactorySetFlags(IntPtr factory, int flags);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    delegate int FactoryCreateDevice(IntPtr factory, IntPtr adapter, int featureLevel,
        ref Guid iid, out IntPtr device);

    static void TryFactory(IntPtr adapter) {
        Guid clsid = new Guid("114863bf-c386-4aee-b39d-8f0bbb062955");
        Guid factoryIid = new Guid("61f307d3-d34e-4e7c-8374-3ba4de23cccb");
        IntPtr factory;
        int result = D3D12GetInterface(ref clsid, ref factoryIid, out factory);
        Report("D3D12GetInterface(ID3D12DeviceFactory)", result);
        if (result < 0) return;
        try {
            IntPtr vtable = Marshal.ReadIntPtr(factory);
            var setFlags = Marshal.GetDelegateForFunctionPointer<FactorySetFlags>(Marshal.ReadIntPtr(vtable, 5 * IntPtr.Size));
            Report("ID3D12DeviceFactory.SetFlags(allow existing/incompatible)", setFlags(factory, 3));
            var create = Marshal.GetDelegateForFunctionPointer<FactoryCreateDevice>(Marshal.ReadIntPtr(vtable, 9 * IntPtr.Size));
            Guid deviceIid = new Guid("e865df17-a9ee-46f9-a463-3098315aa2e5");
            IntPtr device;
            result = create(factory, adapter, 0xb000, ref deviceIid, out device);
            Report("ID3D12DeviceFactory.CreateDevice(WARP, ID3D12Device4)", result);
            Release(device);
            result = D3D12CreateDevice(adapter, 0xb000, ref deviceIid, out device);
            Report("D3D12CreateDevice(WARP, ID3D12Device4)", result);
            Release(device);
        } finally { Release(factory); }
    }

    static void Report(string operation, int result) {
        Console.WriteLine(operation + ": HRESULT 0x" + result.ToString("X8"));
    }

    static void Release(IntPtr value) {
        if (value != IntPtr.Zero) Marshal.Release(value);
    }

    static void TryD3D12(string label, IntPtr adapter) {
        Guid iid = new Guid("189819f1-1db6-4b57-be54-1821339b85f7");
        IntPtr device;
        int result = D3D12CreateDevice(adapter, 0xb000, ref iid, out device);
        Report("D3D12CreateDevice(" + label + ")", result);
        Release(device);
    }

    public static void Run() {
        Guid factoryIid = new Guid("1bc6ea02-ef36-464f-bf0c-21ca39e5168a");
        IntPtr factory;
        int result = CreateDXGIFactory1(ref factoryIid, out factory);
        Report("CreateDXGIFactory1(IDXGIFactory4)", result);
        if (result >= 0) {
            try {
                IntPtr vtable = Marshal.ReadIntPtr(factory);
                IntPtr method = Marshal.ReadIntPtr(vtable, 27 * IntPtr.Size);
                var enumerate = Marshal.GetDelegateForFunctionPointer<EnumWarpAdapter>(method);
                Guid adapterIid = new Guid("29038f61-3839-4626-91fd-086879011a05");
                IntPtr adapter;
                result = enumerate(factory, ref adapterIid, out adapter);
                Report("IDXGIFactory4.EnumWarpAdapter", result);
                if (result >= 0) {
                    try { TryD3D12("explicit WARP", adapter); TryFactory(adapter); }
                    finally { Release(adapter); }
                }
            } finally { Release(factory); }
        }

        TryD3D12("default adapter", IntPtr.Zero);

        Guid coreIid = new Guid("78ee5945-c36e-4b13-a669-005dd11c0f06");
        IntPtr core;
        result = DXCoreCreateAdapterFactory(ref coreIid, out core);
        Report("DXCoreCreateAdapterFactory", result);
        Release(core);

        IntPtr d3d11, context;
        int level;
        result = D3D11CreateDevice(IntPtr.Zero, 5, IntPtr.Zero, 0, IntPtr.Zero,
            0, 7, out d3d11, out level, out context);
        Report("D3D11CreateDevice(WARP)", result);
        Release(context);
        Release(d3d11);
    }
}
'@

[WindowsGraphicsProbe]::Run()

# Log dzn's own adapter/device diagnostics as well as the Vulkan loader's result.
$env:DZN_DEBUG = "d3d12,warp"
$env:MESA_LOG_LEVEL = "debug"
$vulkanInfo = Join-Path $env:VULKAN_SDK "Bin/vulkaninfo.exe"
if (Test-Path $vulkanInfo) {
    & $vulkanInfo --summary
    Write-Output "vulkaninfo exit code: $LASTEXITCODE"
} else {
    Write-Output "vulkaninfo is absent from the stripped SDK"
}
# Failed graphics calls are probe results, not a script failure.
exit 0
