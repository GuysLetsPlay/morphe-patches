param(
    [string]$Branch = "feat/channel-search-buttons",
    [string]$YouTubeApk = "C:\Dev\Apk\com.google.android.youtube_21.16.256-1561068412_minAPI28(arm64-v8a,armeabi-v7a,x86,x86_64)(nodpi)_apkmirror.com.apk",
    [string]$MorpheDesktop = "C:\Dev\tools\morphe-desktop\morphe-desktop-1.18.1-all.jar",
    [string]$Keystore = "C:\Dev\scratch\morphe-manager-signing.keystore",
    [string]$OutputRoot = "C:\Dev\build_artifacts\channel-search-test",
    [switch]$SkipWorkflow
)

$ErrorActionPreference = "Stop"
$ownerRepo = "GuysLetsPlay/morphe-patches"
$workflow = "build_pull_request.yml"
$packageName = "app.morphe.android.youtube.channeltest"
$alias = "Morphe"
$password = ""

foreach ($path in @($YouTubeApk, $MorpheDesktop, $Keystore)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required build input not found: $path"
    }
}

New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$runId = $null
if (-not $SkipWorkflow) {
    $headSha = (git rev-parse HEAD).Trim()
    gh workflow run $workflow --repo $ownerRepo --ref $Branch
    if ($LASTEXITCODE -ne 0) { throw "Could not dispatch the GitHub Actions build." }

    $deadline = (Get-Date).AddMinutes(4)
    do {
        Start-Sleep -Seconds 5
        $runs = gh run list --repo $ownerRepo --workflow $workflow --branch $Branch --limit 5 --json databaseId,status,headSha | ConvertFrom-Json
        $run = $runs | Where-Object { $_.headSha -eq $headSha } | Select-Object -First 1
    } while (-not $run -and (Get-Date) -lt $deadline)
    if (-not $run) { throw "The dispatched workflow run for $headSha was not found." }

    gh run watch $run.databaseId --repo $ownerRepo --exit-status
    if ($LASTEXITCODE -ne 0) { throw "GitHub Actions failed for run $($run.databaseId)." }
    $runId = $run.databaseId
} else {
    $run = gh run list --repo $ownerRepo --workflow $workflow --branch $Branch --limit 1 --json databaseId,status | ConvertFrom-Json | Select-Object -First 1
    if (-not $run -or $run.status -ne "completed") { throw "No completed workflow run found for $Branch." }
    $runId = $run.databaseId
}

$runDir = Join-Path $OutputRoot "run-$runId"
if (Test-Path -LiteralPath $runDir) { Remove-Item -LiteralPath $runDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
gh run download $runId --repo $ownerRepo --dir $runDir
if ($LASTEXITCODE -ne 0) { throw "Could not download artifacts from run $runId." }

$mppFiles = @(Get-ChildItem -LiteralPath $runDir -Filter *.mpp -File -Recurse)
if ($mppFiles.Count -gt 0) {
    $mpp = $mppFiles[0].FullName
} else {
    $bundleDir = Get-ChildItem -LiteralPath $runDir -Directory -Filter *.mpp | Select-Object -First 1
    if (-not $bundleDir) { throw "Run $runId did not contain a Morphe bundle." }
    $mpp = Join-Path $runDir "channel-search.mpp"
    $zip = Join-Path $runDir "channel-search.zip"
    Compress-Archive -Path (Join-Path $bundleDir.FullName "*") -DestinationPath $zip -Force
    Move-Item -LiteralPath $zip -Destination $mpp -Force
}

$apk = Join-Path $runDir "youtube-channel-search-test-clone.apk"
$result = Join-Path $runDir "patch-result-clone.json"
$java = "C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { $java = "java" }

& $java -Xmx5g -jar $MorpheDesktop patch `
    --patches $mpp `
    --enable 'Clone app' --options=packageName=$packageName `
    --enable 'Custom branding' --options='customName=YouTube Channel Test' `
    --enable 'Channel search' `
    --out $apk --result-file $result --striplibs arm64-v8a `
    --keystore $Keystore --keystore-entry-alias $alias --keystore-password=$password --keystore-entry-password $alias `
    $YouTubeApk
if ($LASTEXITCODE -ne 0) { throw "Morphe Desktop failed to build the test APK." }

$buildResult = Get-Content -LiteralPath $result -Raw | ConvertFrom-Json
if ($buildResult.PSObject.Properties.Name -contains "failed" -and $buildResult.failed -gt 0) {
    throw "Morphe Desktop reported $($buildResult.failed) failed patches."
}
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "APK output was not created." }

$devices = @(adb devices | Select-String "\tdevice$")
if ($devices.Count -eq 0) { throw "No ADB device is connected." }
adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw "Could not install the test APK." }
adb shell monkey -p $packageName 1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "APK installed, but Android could not launch the test app." }
Write-Host "Test APK installed and opened on the connected device: $apk"
