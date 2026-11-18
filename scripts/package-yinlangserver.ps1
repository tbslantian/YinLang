$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$build = Join-Path $root 'build'
$zip = Join-Path $build 'yinlangserver-docker.zip'
$upstream = Join-Path $root '.reference/lxserver.zip'
if (-not (Test-Path -LiteralPath $upstream)) { throw 'Place the LXServer v2.2.0 source archive at .reference/lxserver.zip first.' }
New-Item -ItemType Directory -Force -Path $build | Out-Null
$files = @(
    '.reference/lxserver.zip'
    'backend/server.mjs'
    'backend/yinlangserver/.env.example'
    'backend/yinlangserver/.env.nas.example'
    'backend/yinlangserver/README.md'
    'backend/yinlangserver/Dockerfile'
    'backend/yinlangserver/Dockerfile.dockerignore'
    'backend/yinlangserver/compose.yaml'
    'backend/yinlangserver/compose.nas.yaml'
    'backend/yinlangserver/brand.mjs'
    'backend/yinlangserver/mount.mjs'
    'scripts/publish-yinlangserver.ps1'
)
& tar.exe -a -c -f $zip -C $root @files
if ($LASTEXITCODE -ne 0) { throw "tar failed with code $LASTEXITCODE" }
Write-Output $zip
