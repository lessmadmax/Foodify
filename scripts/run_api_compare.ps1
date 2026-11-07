$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$key = $env:OPENAI_API_KEY
if (-not $key) { $key = [Environment]::GetEnvironmentVariable('OPENAI_API_KEY','User') }
if (-not $key) { $key = [Environment]::GetEnvironmentVariable('OPENAI_API_KEY','Machine') }
if (-not $key) { Write-Host 'OPENAI_API_KEY 환경변수를 찾지 못했습니다.'; exit 1 }
$root = Join-Path $PSScriptRoot '..\data\private\ar\api-compare-requests'
$outDir = Join-Path $root 'responses'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$repeat = 3
$files = Get-ChildItem -LiteralPath $root -Filter '*.json' | Where-Object { $_.Name -ne 'manifest.json' }
$total = $files.Count * $repeat; $n = 0
foreach ($f in $files) {
  $body = [IO.File]::ReadAllBytes($f.FullName)
  for ($i = 0; $i -lt $repeat; $i++) {
    $n++
    $target = Join-Path $outDir ("{0}__{1}.json" -f $f.BaseName, $i)
    if (Test-Path -LiteralPath $target) { Write-Host "[$n/$total] $($f.BaseName) #$i 이미 있음"; continue }
    for ($try = 0; $try -lt 4; $try++) {
      try {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $r = Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'https://api.openai.com/v1/responses' `
             -Headers @{ Authorization = "Bearer $key" } -ContentType 'application/json; charset=utf-8' -Body $body -TimeoutSec 120
        $ms = $sw.ElapsedMilliseconds
        $ms2 = New-Object IO.MemoryStream; $r.RawContentStream.Position = 0; $r.RawContentStream.CopyTo($ms2)
        $text = [Text.Encoding]::UTF8.GetString($ms2.ToArray())
        $wrapped = '{"elapsedMs":' + $ms + ',"response":' + $text + '}'
        [IO.File]::WriteAllText($target, $wrapped, (New-Object Text.UTF8Encoding $false))
        Write-Host "[$n/$total] $($f.BaseName) #$i 완료 ($ms ms)"
        break
      } catch {
        $code = $null; try { $code = [int]$_.Exception.Response.StatusCode } catch {}
        Write-Host "[$n/$total] $($f.BaseName) #$i 오류 $code : $($_.Exception.Message)"
        if ($code -in 429,500,502,503,504 -and $try -lt 3) { Start-Sleep -Seconds (5*($try+1)) } else {
          [IO.File]::WriteAllText($target, ('{"error":"HTTP ' + $code + '"}'), (New-Object Text.UTF8Encoding $false)); break }
      }
    }
  }
}
Write-Host '모든 요청 완료.'
