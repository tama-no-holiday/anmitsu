# Splits data/context-holdout/cases.tsv by reading family into dev.tsv and holdout.tsv, checks them and writes
# manifest.json. The split depends on the family and a fixed salt only, never on results. Refuses to overwrite a
# manifest: the holdout is locked once the manifest exists (see protocol.md).
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$dir = Join-Path $root 'data/context-holdout'
$manifest = Join-Path $dir 'manifest.json'
if (Test-Path -LiteralPath $manifest) { throw 'manifest.json exists: the split is locked.' }
$utf8 = [Text.UTF8Encoding]::new($false)
$sha = [Security.Cryptography.SHA256]::Create()
$salt = 'context-holdout-0.1.28:'
$devRepo = @('しかい', 'こうえん', 'きかん', 'かいとう', 'はし', 'あつい', 'きしゃ', 'こうてい', 'せいか', 'しんこう')
$oldRows = [IO.File]::ReadAllLines((Join-Path $root 'data/context-eval.tsv'), $utf8) | Where-Object { $_ }
$oldSurfaces = $oldRows | ForEach-Object { ($_ -split "`t")[3] -split '\|' }

$rows = [IO.File]::ReadAllLines((Join-Path $dir 'cases.tsv'), $utf8) | Where-Object { $_ }
$problems = [Collections.Generic.List[string]]::new()
$dev = [Collections.Generic.List[string]]::new()
$held = [Collections.Generic.List[string]]::new()
$seen = @{}
$n = 0
foreach ($row in $rows) {
    $n++
    $f = $row -split "`t"
    if ($f.Count -ne 5) { $problems.Add("line ${n}: expected 5 fields"); continue }
    $family, $kind, $reading, $accept, $prefix = $f
    if ($kind -notin 'positive', 'neutral', 'contradictory', 'protected') { $problems.Add("line ${n}: kind $kind") }
    if ($reading -cnotmatch '^[ぁ-ゖー]+$') { $problems.Add("line ${n}: reading is not kana") }
    if ($family.TrimStart('c', 't', 'l', '-') -in $devRepo) { $problems.Add("line ${n}: family of the old examples") }
    $key = "$reading`t$prefix"
    if ($seen.ContainsKey($key)) { $problems.Add("line ${n}: duplicate") }
    $seen[$key] = $true
    foreach ($surface in $accept -split '\|') {
        if (!$surface) { $problems.Add("line ${n}: empty surface") }
        if ($surface -in $oldSurfaces) { $problems.Add("line ${n}: surface of an old example") }
        # A kanji run of an accepted surface in the prefix would hand the answer over.
        foreach ($m in [regex]::Matches($surface, '\p{IsCJKUnifiedIdeographs}{2,}')) {
            if ($prefix.Contains($m.Value)) { $problems.Add("line ${n}: prefix contains $($m.Value)") }
        }
    }
    $bytes = $sha.ComputeHash($utf8.GetBytes($salt + $family))
    $line = "cx{0:d3}`t$family`t$kind`t$reading`t$accept`t$prefix" -f $n
    if ($bytes[0] % 3 -eq 0) { $dev.Add($line) } else { $held.Add($line) }
}
if ($problems.Count) { throw ($problems -join "`n") }

$devFamilies = $dev | ForEach-Object { ($_ -split "`t")[1] } | Sort-Object -Unique
$heldFamilies = $held | ForEach-Object { ($_ -split "`t")[1] } | Sort-Object -Unique
$overlap = @($devFamilies | Where-Object { $_ -in $heldFamilies })
if ($overlap.Count) { throw "family in both splits: $overlap" }
[IO.File]::WriteAllText((Join-Path $dir 'dev.tsv'), (($dev -join "`n") + "`n"), $utf8)
[IO.File]::WriteAllText((Join-Path $dir 'holdout.tsv'), (($held -join "`n") + "`n"), $utf8)

function Hash($name) { (Get-FileHash -LiteralPath (Join-Path $dir $name) -Algorithm SHA256).Hash.ToLowerInvariant() }
function Counts($lines) {
    $c = [ordered]@{ positive = 0; neutral = 0; contradictory = 0; protected = 0 }
    foreach ($line in $lines) { $c[($line -split "`t")[2]]++ }
    $c
}
$out = [ordered]@{
    writtenBeforeAnyModelRun = $true
    salt = $salt
    rule = 'sha256(salt + family)[0] % 3 == 0 -> dev, else holdout'
    sha256 = [ordered]@{
        'cases.tsv' = Hash 'cases.tsv'; 'dev.tsv' = Hash 'dev.tsv'; 'holdout.tsv' = Hash 'holdout.tsv'; 'protocol.md' = Hash 'protocol.md'
    }
    dev = [ordered]@{ families = @($devFamilies).Count; cases = $dev.Count; kinds = Counts $dev }
    holdout = [ordered]@{ families = @($heldFamilies).Count; cases = $held.Count; kinds = Counts $held }
    familyOverlap = 0
    oldExampleOverlap = 0
}
[IO.File]::WriteAllText($manifest, ($out | ConvertTo-Json -Depth 5) + "`n", $utf8)
Get-Content -LiteralPath $manifest -Encoding utf8
