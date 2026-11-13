param([switch]$KeepDatabase)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimePath = Join-Path $projectRoot '.tools/native'
$statePath = Join-Path $runtimePath 'processes.json'
if (Test-Path -LiteralPath $statePath) {
  $records = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json
  if (@($records)[0].database -eq 'Supabase') { $KeepDatabase = $true }
  $remaining = @()
  foreach ($record in $records) {
    $process = Get-Process -Id $record.id -ErrorAction SilentlyContinue
    if ($process -and $process.Path -eq $record.executable -and $process.StartTime.ToUniversalTime().Ticks -eq $record.ticks) {
      Stop-Process -Id $process.Id
      if (!$process.WaitForExit(10000)) { throw "Local $($record.name) did not exit in time." }
      Write-Output "Stopped local $($record.name)."
    } elseif ($process) {
      $remaining += $record
      Write-Warning "Refused to stop local $($record.name): its saved process identity does not match."
    }
  }
  if ($remaining.Count) {
    $remaining | ConvertTo-Json | Set-Content -LiteralPath $statePath
    throw 'Some local processes could not be identified safely; their records were retained.'
  }
  Remove-Item -LiteralPath $statePath
}
$pgCtl = Join-Path $runtimePath 'postgresql/pgsql/bin/pg_ctl.exe'
$postgresData = Join-Path $runtimePath 'postgres-data'
if (!$KeepDatabase -and (Test-Path -LiteralPath $pgCtl) -and (Test-Path -LiteralPath (Join-Path $postgresData 'postmaster.pid'))) {
  $stopper = Start-Process -FilePath $pgCtl -ArgumentList "stop -D `"$postgresData`" -m fast -w -t 30" -WindowStyle Hidden -Wait -PassThru
  if ($stopper.ExitCode -ne 0) { throw 'Could not stop the project-local PostgreSQL cluster. Check its log.' }
  Write-Output 'Stopped local PostgreSQL. Data is retained.'
}
