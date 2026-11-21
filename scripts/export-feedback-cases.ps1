$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$rows = @()
foreach ($file in @('data/evaluation.jsonl','data/feedback-regression.jsonl')) {
    foreach ($line in Get-Content (Join-Path $root $file) -Encoding utf8) {
        if (!$line.Trim()) { continue }
        $case = $line | ConvertFrom-Json
        $rows += @($case.id,$case.kind,$case.input,$case.reading,($case.surfaces -join '|'),($case.forbidden -join '|')) -join "`t"
    }
}
$destination = Join-Path $root 'android/core/src/test/resources/feedback.tsv'
[IO.File]::WriteAllText($destination, ($rows -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
Write-Output "Exported $($rows.Count) synthetic development cases."
