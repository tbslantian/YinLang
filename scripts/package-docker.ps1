$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$build = Join-Path $root 'build'
$zip = Join-Path $build 'SoundShelf-Docker.zip'
foreach ($directory in @($build, (Join-Path $root 'shared'))) {
    if (-not (Test-Path -LiteralPath $directory)) { throw 'Build the catalog and create the build directory first.' }
}
foreach ($file in @('shared/catalog.bundle.js','shared/catalog-licenses.txt')) {
    if (-not (Test-Path -LiteralPath (Join-Path $root $file))) { throw 'Run npm run catalog before packaging.' }
}
& tar.exe -a -c -f $zip -C $root .dockerignore README.md backend/Dockerfile backend/compose.yaml backend/server.mjs backend/index.html backend/.env.example shared/catalog.bundle.js shared/catalog-licenses.txt
if ($LASTEXITCODE -ne 0) { throw "tar failed with code $LASTEXITCODE" }
Write-Output $zip
