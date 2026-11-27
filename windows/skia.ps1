param([string]$Out = 'out/win-ft-x64')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$commit = '95ee33d7ec7791ab9c071675710a14fe6408ae4f'
$gnRev = 'b2afae122eeb6ce09c52d63f67dc53fc517dbdc8'
$skia = if ($env:SKIA_DIR) { $env:SKIA_DIR } else { Join-Path $root 'third_party/skia' }
function Run {
    & $args[0] $args[1..($args.Count - 1)]
    if ($LASTEXITCODE -ne 0) { throw "failed ($LASTEXITCODE): $($args -join ' ')" }
}
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vs) { throw 'Visual C++ Build Tools were not found' }
cmd /c "`"$(Join-Path $vs 'VC/Auxiliary/Build/vcvars64.bat')`" >nul && set" | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process') }
}
$llvm = Join-Path $env:ProgramFiles 'LLVM'
if (-not (Test-Path (Join-Path $llvm 'bin/clang-cl.exe'))) { throw 'LLVM with clang-cl was not found' }
$llvmVersion = ((& (Join-Path $llvm 'bin/clang-cl.exe') --version | Select-Object -First 1) -replace '^.*version (\d+)\..*$', '$1')
if (-not (Test-Path $skia)) {
    Run git init -q $skia
    Run git -C $skia remote add origin https://skia.googlesource.com/skia
    Run git -C $skia fetch -q --depth 1 origin $commit
    Run git -C $skia checkout -q FETCH_HEAD
}
if ((git -C $skia rev-parse HEAD) -ne $commit) { throw "third_party/skia is not at $commit" }
Run python (Join-Path $root 'tools/skia_sync.py') $skia
Get-ChildItem (Join-Path $root 'tools/skia-patches') -Filter *.patch | ForEach-Object {
    git -C $skia apply --check $_.FullName 2>$null
    if ($LASTEXITCODE -eq 0) { Run git -C $skia apply $_.FullName; return }
    git -C $skia apply --reverse --check $_.FullName 2>$null
    if ($LASTEXITCODE -ne 0) { throw "cannot apply patch $($_.Name)" }
}
$gnDir = Join-Path $root 'third_party/gn-src'
$gn = Join-Path $gnDir 'out/gn.exe'
if (-not (Test-Path $gn)) {
    if (Test-Path $gnDir) { Remove-Item $gnDir -Recurse -Force }
    New-Item -ItemType Directory $gnDir | Out-Null
    Run git -C $gnDir init -q
    Run git -C $gnDir remote add origin https://gn.googlesource.com/gn
    Run git -C $gnDir fetch -q --depth 1 origin $gnRev
    Run git -C $gnDir checkout -q FETCH_HEAD
    Push-Location $gnDir
    Run python build/gen.py --no-last-commit-position
    Pop-Location
    $short = $gnRev.Substring(0, 12)
    Set-Content (Join-Path $gnDir 'out/last_commit_position.h') -Encoding ascii -Value "#ifndef OUT_LAST_COMMIT_POSITION_H_`n#define OUT_LAST_COMMIT_POSITION_H_`n#define LAST_COMMIT_POSITION_NUM 0`n#define LAST_COMMIT_POSITION `"0 ($short)`"`n#endif"
    $env:CL = '/FIstring /FIcstdint /FIalgorithm /FIcstring'
    Run ninja -k 0 -C (Join-Path $gnDir 'out') gn.exe
    Remove-Item Env:CL
}
$outDir = Join-Path $skia $Out
New-Item -ItemType Directory $outDir -Force | Out-Null
$llvmGn = $llvm -replace '\\', '/'
$gnArgs = "target_os = `"win`"`ntarget_cpu = `"x64`"`nclang_win = `"$llvmGn`"`nclang_win_version = $llvmVersion`n" + (Get-Content (Join-Path $PSScriptRoot 'skia-args.gn') -Raw)
Set-Content (Join-Path $outDir 'args.gn') -Value $gnArgs -Encoding ascii
Run $gn gen $outDir "--root=$skia"
Run ninja -C $outDir skia pathops skparagraph skshaper skunicode_core skunicode_icu
$pathops = Get-ChildItem (Join-Path $outDir 'obj/src/pathops') -Filter *.obj | ForEach-Object { $_.FullName }
Run lib "/OUT:$(Join-Path $outDir 'pathops.lib')" @pathops
if (-not (Test-Path (Join-Path $outDir 'icudtl.dat'))) {
    Copy-Item (Join-Path $skia 'third_party/externals/icu/flutter/icudtl.dat') (Join-Path $outDir 'icudtl.dat')
}
Get-ChildItem $outDir -Filter *.lib | Select-Object Name, Length
