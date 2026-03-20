[CmdletBinding()]
param(
    [string]$Profile,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$JarPath = "target/broker-mcp-0.0.1-SNAPSHOT.jar"
$SessionFile = Join-Path (Join-Path $HOME ".broker-mcp") ".env.session"

function Load-EnvFile([string]$Path) {
    if (-not (Test-Path $Path)) { return 0 }
    $count = 0
    foreach ($line in Get-Content $Path) {
        $line = $line.Trim()
        if (-not $line -or $line.StartsWith("#")) { continue }
        if ($line -match '^([A-Za-z_]\w*)\s*=\s*(.*)$') {
            $val = $Matches[2].Trim()
            if ($val.Length -ge 2 -and (($val[0] -eq "'" -and $val[-1] -eq "'") -or ($val[0] -eq '"' -and $val[-1] -eq '"'))) {
                $val = $val.Substring(1, $val.Length - 2)
            }
            [Environment]::SetEnvironmentVariable($Matches[1], $val, "Process")
            $count++
        }
    }
    return $count
}

$n = Load-EnvFile $SessionFile

if ($Profile) {
    [Environment]::SetEnvironmentVariable("SPRING_PROFILES_ACTIVE", $Profile, "Process")
}

$resolvedJar = Resolve-Path $JarPath -ErrorAction Stop
$active = if ($Profile) { $Profile } else { $env:SPRING_PROFILES_ACTIVE }

Write-Host "Loaded $n vars from $SessionFile"
if ($active) { Write-Host "SPRING_PROFILES_ACTIVE=$active" }
Write-Host "Launching: $resolvedJar"

if ($DryRun) { return }

if ($active) {
    & java "-Dspring.profiles.active=$active" -jar $resolvedJar
} else {
    & java -jar $resolvedJar
}
