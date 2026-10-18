# Rebuilds the plugin and restarts the sandbox test IDE, without ever touching the user's own IntelliJ.
#
#   powershell -ExecutionPolicy Bypass -File scripts/restart-test-ide.ps1            build, close test IDE, relaunch
#   powershell -ExecutionPolicy Bypass -File scripts/restart-test-ide.ps1 -NoBuild   relaunch only
#
# The test IDE is identified ONLY by the PID in the sandbox's system/.pid file, and only if that process is
# idea64 and started within 2 minutes of the file being written (see CLAUDE.md -> Build & run). Anything
# else stops the script with exit code 2 so Claude asks the user instead of guessing.
param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'

$project = Split-Path -Parent $PSScriptRoot
$sandbox = Join-Path $env:USERPROFILE '.idea-sandbox\git-dashboard'
$pidFile = Join-Path $sandbox 'system\.pid'
$log = Join-Path $sandbox 'log\idea.log'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$mvn = 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.2.3\plugins\maven-plugin\lib\maven3\bin\mvn.cmd'

function Get-TestIde {
    if (-not (Test-Path $pidFile)) { return $null }
    $id = [int](Get-Content $pidFile -Raw).Trim()
    $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
    if (-not $proc) { return $null }  # stale .pid: test IDE already closed
    $written = (Get-Item $pidFile).LastWriteTime
    if ($proc.ProcessName -ne 'idea64' -or [math]::Abs(($proc.StartTime - $written).TotalSeconds) -gt 120) {
        Write-Output "UNSAFE: PID $id is '$($proc.ProcessName)', started $($proc.StartTime), .pid written $written. Not closing it - ask the user."
        exit 2
    }
    return $proc
}

# 1. Compile first, so a broken build never costs the running test IDE.
if (-not $NoBuild) {
    Push-Location $project
    try {
        $out = & $mvn -B -ntp -q package 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) {
            Write-Output 'BUILD FAILED:'
            $out -split "`n" | Where-Object { $_ -match '\[ERROR\] file|^e: |^w: |BUILD FAILURE' } | Select-Object -First 30
            exit 1
        }
        Write-Output 'Build OK.'
    } finally { Pop-Location }
}

# 2. Close the test IDE (politely, then by its PID only).
$ide = Get-TestIde
if ($ide) {
    Write-Output "Closing test IDE (PID $($ide.Id), '$($ide.MainWindowTitle)')..."
    [void]$ide.CloseMainWindow()
    if (-not $ide.WaitForExit(30000)) { Stop-Process -Id $ide.Id -Force; [void]$ide.WaitForExit(10000) }
} else {
    Write-Output 'Test IDE was not running.'
}

# 3. Launch (exec:exec is async, so Maven returns once the IDE has started).
$launched = Get-Date
Push-Location $project
try {
    # The package phase above already installed the jar into the sandbox; exec alone is enough.
    & $mvn -B -ntp -q exec:exec@run-ide 2>&1 | Out-Null
} finally { Pop-Location }

# 4. Wait for the plugin to load and report problems from our own code (log lines from this launch only;
#    idea.log keeps earlier runs, and each line starts "yyyy-MM-dd HH:mm:ss,fff").
function Test-SinceLaunch($line) {
    $t = [datetime]::MinValue
    [datetime]::TryParseExact($line.Substring(0, [math]::Min(19, $line.Length)), 'yyyy-MM-dd HH:mm:ss', $null, 'None', [ref]$t) -and $t -ge $launched.AddSeconds(-2)
}
$deadline = $launched.AddSeconds(120)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    if ((Test-Path $log) -and (Get-Item $log).LastWriteTime -gt $launched) {
        $loaded = Select-String -Path $log -Pattern 'Loaded custom plugins' -SimpleMatch | Where-Object { Test-SinceLaunch $_.Line } | Select-Object -Last 1
        if ($loaded) {
            Write-Output "Test IDE running (PID $((Get-Content $pidFile -Raw).Trim()))."
            Write-Output $loaded.Line.Trim()
            $errors = Select-String -Path $log -Pattern 'ERROR.*(dev\.gitdashboard|Git Dashboard)' | Where-Object { Test-SinceLaunch $_.Line } | Select-Object -Last 10
            if ($errors) { Write-Output 'Plugin errors in idea.log:'; $errors | ForEach-Object { $_.Line.Trim() } } else { Write-Output 'No plugin errors in idea.log.' }
            exit 0
        }
    }
}
Write-Output "Test IDE did not report 'Loaded custom plugins' within 2 minutes; check $log"
exit 3
