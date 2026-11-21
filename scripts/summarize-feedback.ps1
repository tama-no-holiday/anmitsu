param([Parameter(Mandatory)][string]$Log, [Parameter(Mandatory)][string]$Output)
$ErrorActionPreference = 'Stop'
$lines = @(Get-Content -LiteralPath $Log -Encoding utf8 | Where-Object { $_.StartsWith("CASE`t") })
if ($lines.Count -ne 51) { throw 'Expected header and 50 development cases.' }
$cases = @($lines | ConvertFrom-Csv -Delimiter "`t" | ForEach-Object {
    [ordered]@{ id=$_.id;kind=$_.kind;top1=($_.top1 -eq 'true');recall10=($_.recall10 -eq 'true');reading_changed=($_.readingChanged -eq 'true');forbidden=($_.forbidden -eq 'true');host_p50_ms=[double]::Parse($_.p50ms,[Globalization.CultureInfo]::InvariantCulture);host_p95_ms=[double]::Parse($_.p95ms,[Globalization.CultureInfo]::InvariantCulture) }
})
$feedback = @($cases | Select-Object -Skip 25)
$clean = @($feedback | Where-Object kind -ne typo)
$report = [ordered]@{
    scope='Synthetic observed development cases; no touch/personalization; host JVM only, not device performance or blind accuracy.'
    warm_runs_per_case=10
    all_top1=@($cases | Where-Object top1).Count
    all_count=$cases.Count
    feedback_top1=@($feedback | Where-Object top1).Count
    feedback_count=$feedback.Count
    feedback_clean_reading_changed=@($clean | Where-Object reading_changed).Count
    feedback_clean_count=$clean.Count
    cases=$cases
}
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $Output -Encoding utf8
Write-Output "All Top1: $($report.all_top1)/50; feedback: $($report.feedback_top1)/25; clean/unknown reading changes: $($report.feedback_clean_reading_changed)/22"
