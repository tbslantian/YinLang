param(
    [ValidatePattern('^[a-z0-9][a-z0-9_-]{1,38}$')]
    [string]$DockerHubUser = 'tbslantian',
    [ValidatePattern('^linux/(amd64|arm64)(,linux/(amd64|arm64))?$')]
    [string]$Platforms = 'linux/amd64,linux/arm64',
    [switch]$UpdateLatest
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$image = "docker.io/$DockerHubUser/yinlangserver"
$release = "${image}:2.2.0-3"
$dockerfile = Join-Path $root 'backend/yinlangserver/Dockerfile'

$tags = @('--tag', $release)
if ($UpdateLatest) { $tags += @('--tag', "${image}:latest") }
& docker buildx build --file $dockerfile --platform $Platforms @tags --push $root
if ($LASTEXITCODE -ne 0) { throw "Docker build or push failed with code $LASTEXITCODE" }
& docker buildx imagetools inspect $release
if ($LASTEXITCODE -ne 0) { throw "Docker Hub manifest verification failed with code $LASTEXITCODE" }
