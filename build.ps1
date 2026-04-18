$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

# Use current JAVA_HOME if present; otherwise rely on PATH.
mvn clean package -q
