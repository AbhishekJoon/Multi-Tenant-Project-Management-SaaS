param(
  [switch]$SkipBuild,
  [switch]$ReuseRunning,
  [ValidateSet('Local', 'Supabase')][string]$Database = 'Local',
  [string]$EnvFile
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimePath = Join-Path $projectRoot '.tools/native'
$postgresBin = Join-Path $runtimePath 'postgresql/pgsql/bin'
$postgresData = Join-Path $runtimePath 'postgres-data'
$mailpitExe = [System.IO.Path]::GetFullPath((Join-Path $runtimePath 'mailpit/mailpit.exe'))
$javaExe = (Get-Command java.exe).Source
$nodeExe = (Get-Command node.exe).Source
$statePath = Join-Path $runtimePath 'processes.json'

function Assert-Exit([string]$Action) {
  if ($LASTEXITCODE -ne 0) { throw "$Action failed (exit $LASTEXITCODE)." }
}
function Quote-Path([string]$Value) { return '"' + $Value + '"' }
function Wait-Http([string]$Url) {
  $deadline = (Get-Date).AddSeconds(90)
  do {
    if ($backend -and $backend.HasExited) { throw 'Backend exited before becoming ready. Check .tools/native/backend.stdout.log and the selected environment file.' }
    try { $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3; if ($response.StatusCode -eq 200) { return } } catch { }
    Start-Sleep -Milliseconds 500
  } while ((Get-Date) -lt $deadline)
  throw "Service did not become ready: $Url. Check logs under .tools/native."
}

if (($Database -eq 'Local' -and !(Test-Path -LiteralPath (Join-Path $postgresBin 'pg_ctl.exe'))) -or !(Test-Path -LiteralPath $mailpitExe)) {
  throw 'Portable PostgreSQL and Mailpit are required. See docs/deployment/LOCAL_WINDOWS.md.'
}
if (Test-Path -LiteralPath $statePath) {
  if ($ReuseRunning) {
    $saved = Get-Content -Raw -LiteralPath $statePath | ConvertFrom-Json
    $saved = @($saved)
    $canReuse = $saved.Count -eq 3 -and ((@($saved.name | Sort-Object) -join ',') -eq 'backend,frontend,mailpit')
    foreach ($record in $saved) {
      $existing = Get-Process -Id $record.id -ErrorAction SilentlyContinue
      if (!$existing -or $record.database -ne $Database -or $existing.Path -ne $record.executable -or $existing.StartTime.ToUniversalTime().Ticks -ne $record.ticks) {
        $canReuse = $false
      }
    }
    if ($canReuse) {
      foreach ($url in @('http://127.0.0.1:8025/readyz', 'http://127.0.0.1:8080/actuator/health', 'http://127.0.0.1:5173')) {
        try {
          if ((Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3).StatusCode -ne 200) { $canReuse = $false }
        } catch { $canReuse = $false }
      }
      if ($canReuse) {
        Write-Output 'TeamFlow is already running locally: http://localhost:5173'
        return
      }
    }
  }
  throw 'A local process record already exists. Run scripts/stop-local.ps1 before starting again.'
}
$ports = @(1025, 8025, 8080, 5173)
if ($Database -eq 'Local') { $ports += 5432 }
foreach ($port in $ports) {
  $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
  try { $listener.Start() } catch { throw "Port $port is already in use. Stop its existing service before starting TeamFlow." }
  finally { $listener.Stop() }
}
if (!$SkipBuild) {
  Push-Location (Join-Path $projectRoot 'backend')
  try { & ./mvnw.cmd -B -ntp package -DskipTests; Assert-Exit 'Backend build' } finally { Pop-Location }
}
$jar = Join-Path $projectRoot 'backend/target/teamflow-0.1.0-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath $jar)) { throw 'Backend jar is missing. Run the script without -SkipBuild.' }
$vite = Join-Path $projectRoot 'frontend/node_modules/vite/bin/vite.js'
if (!(Test-Path -LiteralPath $vite)) { throw 'Run npm ci in frontend first.' }

if (!$EnvFile) { $EnvFile = if ($Database -eq 'Supabase') { '.env.supabase.local' } else { '.env' } }
$environmentPath = if ([System.IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $projectRoot $EnvFile }
$configuration = @{}
foreach ($line in Get-Content -LiteralPath $environmentPath) {
  if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $configuration[$Matches[1]] = $Matches[2] }
}
if ($Database -eq 'Local' -and !$configuration['DATABASE_PASSWORD']) { throw 'Set DATABASE_PASSWORD in the ignored .env file.' }
$databaseUser = if ($configuration['DATABASE_USER']) { $configuration['DATABASE_USER'] } else { 'teamflow' }
$databaseName = if ($configuration['DATABASE_NAME']) { $configuration['DATABASE_NAME'] } else { 'teamflow' }
foreach ($name in @($databaseUser, $databaseName)) {
  if ($name -notmatch '^[a-zA-Z][a-zA-Z0-9_]*$') { throw 'Local database names must use letters, numbers and underscores.' }
}
$settings = @{
  DATABASE_PASSWORD = $configuration['DATABASE_PASSWORD']; DATABASE_USER = $databaseUser
  DATABASE_URL = "jdbc:postgresql://127.0.0.1:5432/$databaseName"
  PGPASSWORD = $configuration['DATABASE_PASSWORD']; SPRING_PROFILES_ACTIVE = 'dev'
  SMTP_HOST = '127.0.0.1'; SMTP_PORT = '1025'; SMTP_AUTH = 'false'; SMTP_STARTTLS = 'false'
  SMTP_USER = ''; SMTP_PASSWORD = ''; APP_ORIGIN = 'http://localhost:5173'
  SERVER_ADDRESS = '127.0.0.1'; SECURE_COOKIES = 'false'; OPENAPI_ENABLED = 'true'; DEBUG = 'false'
}
if ($Database -eq 'Supabase') {
  foreach ($required in @('SUPABASE_DB_URL', 'SUPABASE_DB_USER', 'SUPABASE_DB_PASSWORD', 'SUPABASE_DB_SSL_ROOT_CERT', 'SUPABASE_URL')) {
    if (!$configuration[$required]) { throw "Set $required in .env.supabase.local before selecting Supabase." }
  }
  if (!$configuration['SUPABASE_PUBLISHABLE_KEY'] -and !$configuration['SUPABASE_ANON_KEY']) { throw 'Set SUPABASE_PUBLISHABLE_KEY in .env.supabase.local to enable Supabase Auth.' }
  $certificatePath = $configuration['SUPABASE_DB_SSL_ROOT_CERT']
  if (![System.IO.Path]::IsPathRooted($certificatePath)) { $certificatePath = Join-Path $projectRoot $certificatePath }
  if (!(Test-Path -LiteralPath $certificatePath -PathType Leaf)) { throw 'Download the Supabase CA certificate and set SUPABASE_DB_SSL_ROOT_CERT before starting.' }
  foreach ($key in $configuration.Keys) {
    if ($key -like 'SUPABASE_DB_*' -or $key -in @('SUPABASE_URL', 'SUPABASE_PUBLISHABLE_KEY', 'SUPABASE_ANON_KEY')) { $settings[$key] = $configuration[$key] }
  }
  $settings.Remove('DATABASE_PASSWORD'); $settings.Remove('DATABASE_USER')
  $settings.Remove('DATABASE_URL'); $settings.Remove('PGPASSWORD')
  $settings['SPRING_PROFILES_ACTIVE'] = 'dev,supabase'
}
$previous = @{}
$processes = @()
try {
  foreach ($key in $settings.Keys) { $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process'); [Environment]::SetEnvironmentVariable($key, $settings[$key], 'Process') }
  if ($Database -eq 'Local') {
    if (!(Test-Path -LiteralPath (Join-Path $postgresData 'PG_VERSION'))) {
      $passwordFile = Join-Path $runtimePath 'initdb-password.tmp'
      try {
        [System.IO.File]::WriteAllText($passwordFile, $configuration['DATABASE_PASSWORD'])
        & (Join-Path $postgresBin 'initdb.exe') -D $postgresData -U $databaseUser --encoding=UTF8 --locale=C --auth-host=scram-sha-256 --auth-local=scram-sha-256 "--pwfile=$passwordFile"
        Assert-Exit 'PostgreSQL initialization'
      } finally { if (Test-Path -LiteralPath $passwordFile) { Remove-Item -LiteralPath $passwordFile } }
    }
    $pgArgs = "start -D $(Quote-Path $postgresData) -l $(Quote-Path (Join-Path $runtimePath 'postgres.log')) -o `"-h 127.0.0.1 -p 5432`" -w -t 30"
    $starter = Start-Process -FilePath (Join-Path $postgresBin 'pg_ctl.exe') -ArgumentList $pgArgs -WindowStyle Hidden -PassThru
    if (!$starter.WaitForExit(40000)) { throw 'PostgreSQL startup command timed out.' }
    $starter.Refresh()
    if ($starter.ExitCode -ne 0) { throw 'PostgreSQL startup failed. Check .tools/native/postgres.log.' }
    $psql = Join-Path $postgresBin 'psql.exe'
    $exists = & $psql -h 127.0.0.1 -p 5432 -U $databaseUser -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$databaseName'"
    Assert-Exit 'Database lookup'
    if ($exists -ne '1') {
      & (Join-Path $postgresBin 'createdb.exe') -h 127.0.0.1 -p 5432 -U $databaseUser $databaseName
      Assert-Exit 'Database creation'
      $backup = Join-Path $runtimePath 'teamflow-backup.sql'
      if (Test-Path -LiteralPath $backup) {
        & $psql -h 127.0.0.1 -p 5432 -U $databaseUser -d $databaseName -v ON_ERROR_STOP=1 -f $backup
        Assert-Exit 'Existing TeamFlow data restore'
      }
    }
  }
  $mail = Start-Process -FilePath $mailpitExe -ArgumentList "--listen 127.0.0.1:8025 --smtp 127.0.0.1:1025 --database $(Quote-Path (Join-Path $runtimePath 'mailpit.db')) --disable-version-check" -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimePath 'mailpit.stdout.log') -RedirectStandardError (Join-Path $runtimePath 'mailpit.stderr.log') -PassThru
  $processes += @{ name = 'mailpit'; id = $mail.Id; ticks = $mail.StartTime.ToUniversalTime().Ticks; executable = $mailpitExe; database = $Database }
  $processes | ConvertTo-Json | Set-Content -LiteralPath $statePath
  $backend = Start-Process -FilePath $javaExe -ArgumentList "-jar $(Quote-Path $jar)" -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimePath 'backend.stdout.log') -RedirectStandardError (Join-Path $runtimePath 'backend.stderr.log') -PassThru
  $processes += @{ name = 'backend'; id = $backend.Id; ticks = $backend.StartTime.ToUniversalTime().Ticks; executable = $javaExe; database = $Database }
  $processes | ConvertTo-Json | Set-Content -LiteralPath $statePath
  $frontend = Start-Process -FilePath $nodeExe -ArgumentList "$(Quote-Path $vite) --host 127.0.0.1 --port 5173 --strictPort" -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimePath 'frontend.stdout.log') -RedirectStandardError (Join-Path $runtimePath 'frontend.stderr.log') -PassThru
  $processes += @{ name = 'frontend'; id = $frontend.Id; ticks = $frontend.StartTime.ToUniversalTime().Ticks; executable = $nodeExe; database = $Database }
  $processes | ConvertTo-Json | Set-Content -LiteralPath $statePath
  Wait-Http 'http://127.0.0.1:8025/readyz'
  Wait-Http 'http://127.0.0.1:8080/actuator/health'
  Wait-Http 'http://127.0.0.1:5173'
  Write-Output 'TeamFlow is running locally: http://localhost:5173'
  Write-Output 'Backend: http://localhost:8080 | Email inbox: http://localhost:8025'
} catch {
  & (Join-Path $PSScriptRoot 'stop-local.ps1') -KeepDatabase:($Database -eq 'Supabase')
  throw
} finally {
  foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
}
