param([switch]$PrivateSchema)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$postgresBin = Join-Path $projectRoot '.tools/native/postgresql/pgsql/bin'
$environmentFile = Join-Path $projectRoot '.env'
$configuration = @{}
foreach ($line in Get-Content -LiteralPath $environmentFile) {
  if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $configuration[$Matches[1]] = $Matches[2] }
}
if (!$configuration['DATABASE_PASSWORD']) { throw 'Local database credentials are missing from .env.' }
$databaseUser = if ($configuration['DATABASE_USER']) { $configuration['DATABASE_USER'] } else { 'teamflow' }
$databaseName = 'teamflow_test_' + ([Guid]::NewGuid().ToString('N'))
$psql = Join-Path $postgresBin 'psql.exe'
$settings = @{
  PGPASSWORD = $configuration['DATABASE_PASSWORD']
  TEAMFLOW_TEST_DATABASE_URL = "jdbc:postgresql://127.0.0.1:5432/$databaseName"
  TEAMFLOW_TEST_DATABASE_USER = $databaseUser
  TEAMFLOW_TEST_DATABASE_PASSWORD = $configuration['DATABASE_PASSWORD']
  TEAMFLOW_TEST_PRIVATE_SCHEMA = if ($PrivateSchema) { 'true' } else { 'false' }
  DEBUG = 'false'
}
$previous = @{}
$created = $false
$createdRoles = @()
$testExit = 1
try {
  foreach ($key in $settings.Keys) { $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process'); [Environment]::SetEnvironmentVariable($key, $settings[$key], 'Process') }
  & (Join-Path $postgresBin 'createdb.exe') -h 127.0.0.1 -p 5432 -U $databaseUser $databaseName
  if ($LASTEXITCODE -ne 0) { throw 'Could not create an isolated test database. Start local PostgreSQL first.' }
  $created = $true
  if ($PrivateSchema) {
    foreach ($role in @('anon', 'authenticated', 'service_role')) {
      $exists = & $psql -h 127.0.0.1 -p 5432 -U $databaseUser -d $databaseName -tAc "SELECT 1 FROM pg_roles WHERE rolname='$role'"
      if ($LASTEXITCODE -ne 0) { throw 'Test role lookup failed.' }
      if ($exists -ne '1') {
        & $psql -h 127.0.0.1 -p 5432 -U $databaseUser -d $databaseName -v ON_ERROR_STOP=1 -c "CREATE ROLE $role NOLOGIN"
        if ($LASTEXITCODE -ne 0) { throw 'Test role creation failed.' }
        $createdRoles += $role
      }
    }
    & $psql -h 127.0.0.1 -p 5432 -U $databaseUser -d $databaseName -v ON_ERROR_STOP=1 -f (Join-Path $projectRoot 'backend/supabase/bootstrap.sql')
    if ($LASTEXITCODE -ne 0) { throw 'Private-schema bootstrap failed.' }
    # Deliberately add unsafe fixture grants only inside the disposable database.
    # This proves the V3 migration actively removes grants rather than passing on clean defaults.
    & $psql -h 127.0.0.1 -p 5432 -U $databaseUser -d $databaseName -v ON_ERROR_STOP=1 -c 'GRANT USAGE ON SCHEMA teamflow TO PUBLIC, anon, authenticated, service_role; ALTER DEFAULT PRIVILEGES IN SCHEMA teamflow GRANT ALL ON TABLES TO anon, authenticated, service_role;'
    if ($LASTEXITCODE -ne 0) { throw 'Private-schema grant fixture failed.' }
  }
  Push-Location (Join-Path $projectRoot 'backend')
  try {
    & ./mvnw.cmd -B -ntp test jacoco:report jacoco:check@security-coverage
    $testExit = $LASTEXITCODE
  } finally { Pop-Location }
} finally {
  if ($created) {
    # Only the randomly generated test database can be removed. Never accept a database name as input.
    if ($databaseName -notmatch '^teamflow_test_[a-f0-9]{32}$') { throw 'Unsafe test database cleanup target.' }
    & (Join-Path $postgresBin 'dropdb.exe') -h 127.0.0.1 -p 5432 -U $databaseUser --force $databaseName
    if ($LASTEXITCODE -ne 0) { Write-Warning "Test database cleanup failed: $databaseName" }
    foreach ($role in $createdRoles) {
      & $psql -h 127.0.0.1 -p 5432 -U $databaseUser -d postgres -v ON_ERROR_STOP=1 -c "DROP ROLE $role"
    }
  }
  foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
}
exit $testExit
