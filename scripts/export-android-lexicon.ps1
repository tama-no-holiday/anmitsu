$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$lexicon = Get-Content (Join-Path $root 'data/lexicon.json') -Raw -Encoding utf8 | ConvertFrom-Json
$rows = @("reading`tsurface`tcost`tpos")
foreach ($entry in $lexicon.entries) {
    $fields = @($entry[0], $entry[1], ([double]$entry[2]).ToString([Globalization.CultureInfo]::InvariantCulture), $entry[3])
    if ($fields | Where-Object { $_ -match "[`t`r`n]" }) { throw 'TSV field contains control characters' }
    $rows += $fields -join "`t"
}
foreach ($relative in @('android/app/src/main/assets/lexicon.tsv', 'android/core/src/test/resources/lexicon.tsv')) {
    $destination = Join-Path $root $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $destination -Parent) | Out-Null
    [IO.File]::WriteAllText($destination, ($rows -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
}
Write-Output "Exported $($lexicon.entries.Count) handwritten lexicon entries."
