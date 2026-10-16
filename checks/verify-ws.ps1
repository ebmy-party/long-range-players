param(
    [Parameter(Mandatory)][string]$OldJar,
    [Parameter(Mandatory)][string]$NewJar,
    [Parameter(Mandatory)][string]$GsonJar,
    [Parameter(Mandatory)][string]$JavaHome
)
$ErrorActionPreference = 'Stop'
$taskOld = (Resolve-Path -LiteralPath $OldJar).Path
$taskNew = (Resolve-Path -LiteralPath $NewJar).Path
$taskGson = (Resolve-Path -LiteralPath $GsonJar).Path
if ((Get-FileHash -LiteralPath $taskOld -Algorithm SHA256).Hash.ToLowerInvariant() -ne '89dc69c259d3f709aba30b166e9db5abbef4f941282c39f7a11b0049980cbf10') { throw 'Old JAR does not match the report' }
$taskTemporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$taskTemporary = Join-Path $taskTemporaryRoot ('lrp-ws-check-' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $taskTemporary | Out-Null
try {
    & (Join-Path $JavaHome 'bin\javac.exe') -cp $taskGson -d $taskTemporary (Join-Path $PSScriptRoot 'WsProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'Probe compile failed' }
    $taskClasspath = $taskTemporary + [IO.Path]::PathSeparator + $taskGson
    & (Join-Path $JavaHome 'bin\java.exe') -cp $taskClasspath WsProbe $taskOld true
    if ($LASTEXITCODE -ne 0) { throw 'Old JAR did not reproduce the expected behavior' }
    & (Join-Path $JavaHome 'bin\java.exe') -cp $taskClasspath WsProbe $taskNew false
    if ($LASTEXITCODE -ne 0) { throw 'New JAR failed to reject plaintext' }
} finally {
    $taskResolved = (Resolve-Path -LiteralPath $taskTemporary).Path
    $taskPrefix = Join-Path $taskTemporaryRoot 'lrp-ws-check-'
    if (!$taskResolved.StartsWith($taskPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected temporary path; cleanup refused' }
    Remove-Item -LiteralPath $taskResolved -Recurse -Force
}
