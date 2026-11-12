$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimePath = Join-Path $projectRoot '.tools/native'
$statePath = Join-Path $runtimePath 'processes.json'
if (Test-Path -LiteralPath $statePath) {
  $records = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json
  foreach ($record in $records) {
    $process = Get-Process -Id $record.id -ErrorAction SilentlyContinue
    if ($process -and $process.Path -eq $record.executable -and $process.StartTime.ToUniversalTime().Ticks -eq $record.ticks) {
      Stop-Process -Id $process.Id
      Write-Output "Stopped local $($record.name)."
    }
  }
  Remove-Item -LiteralPath $statePath
}
$pgCtl = Join-Path $runtimePath 'postgresql/pgsql/bin/pg_ctl.exe'
$postgresData = Join-Path $runtimePath 'postgres-data'
if ((Test-Path -LiteralPath $pgCtl) -and (Test-Path -LiteralPath (Join-Path $postgresData 'postmaster.pid'))) {
  $stopper = Start-Process -FilePath $pgCtl -ArgumentList "stop -D `"$postgresData`" -m fast -w -t 30" -WindowStyle Hidden -Wait -PassThru
  if ($stopper.ExitCode -ne 0) { throw 'Could not stop the project-local PostgreSQL cluster. Check its log.' }
  Write-Output 'Stopped local PostgreSQL. Data is retained.'
}
