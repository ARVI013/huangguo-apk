# Manual APK build (no Gradle): aapt2 -> javac -> d8 -> zip -> zipalign -> apksigner
# Native tools report diagnostics on stderr; PowerShell 7 turns those into terminating
# errors under "Stop", so we gate on $LASTEXITCODE explicitly instead.
$ErrorActionPreference = "Continue"

$root  = "D:\OneDrive\Desktop\DSH Work\huangguo-apk"
$tools = Join-Path $root "tools"
$app   = Join-Path $root "app"
$build = Join-Path $root "build"
$dist  = Join-Path $root "dist"
# The signing key lives OUTSIDE build/: build/ is wiped on every run, and losing the
# key would make in-place upgrades impossible (Android rejects a different signer).
$ksDir = Join-Path $root "keystore"
$ks    = Join-Path $ksDir "release.keystore"

$minSdk = 24
$targetSdk = 35
$versionCode = 2
$versionName = "1.1"
$outApk = Join-Path $dist "huangguo-shorts-$versionName.apk"

function Find-Tool([string]$name) {
    $hit = Get-ChildItem $tools -Recurse -Filter $name -ErrorAction SilentlyContinue |
        Sort-Object { $_.FullName.Length } | Select-Object -First 1
    if (-not $hit) { throw "required tool not found under tools/: $name" }
    return $hit.FullName
}

Write-Host "== locating tools =="
$aapt2      = Find-Tool "aapt2.exe"
$androidJar = Find-Tool "android.jar"
$javac      = Find-Tool "javac.exe"
$java       = Find-Tool "java.exe"
$keytool    = Find-Tool "keytool.exe"
$zipalign   = Find-Tool "zipalign.exe"
$d8Jar      = Find-Tool "d8.jar"
$signJar    = Find-Tool "apksigner.jar"
Write-Host "aapt2      = $aapt2"
Write-Host "android.jar= $androidJar"
Write-Host "javac      = $javac"
Write-Host "d8         = $d8Jar"
Write-Host "apksigner  = $signJar"

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Path $build -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $build "classes") -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $build "dex") -Force | Out-Null
New-Item -ItemType Directory -Path $dist -Force | Out-Null

Write-Host "`n== 1. aapt2 compile =="
& $aapt2 compile --dir (Join-Path $app "res") -o (Join-Path $build "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed ($LASTEXITCODE)" }

Write-Host "`n== 2. aapt2 link =="
$baseApk = Join-Path $build "base.apk"
New-Item -ItemType Directory -Path (Join-Path $build "gen") -Force | Out-Null
& $aapt2 link `
    -o $baseApk `
    -I $androidJar `
    --java (Join-Path $build "gen") `
    --manifest (Join-Path $app "AndroidManifest.xml") `
    --min-sdk-version $minSdk `
    --target-sdk-version $targetSdk `
    --version-code $versionCode `
    --version-name $versionName `
    (Join-Path $build "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed ($LASTEXITCODE)" }

Write-Host "`n== 3. javac =="
$env:JAVA_TOOL_OPTIONS = "-Duser.language=en"
$sources = @()
$sources += Get-ChildItem (Join-Path $app "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem (Join-Path $build "gen") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $androidJar -classpath $androidJar `
    -d (Join-Path $build "classes") $sources 2>&1 | ForEach-Object { Write-Host "$_" }
if ($LASTEXITCODE -ne 0) { throw "javac failed ($LASTEXITCODE)" }

Write-Host "`n== 4. d8 =="
$classes = Get-ChildItem (Join-Path $build "classes") -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $java -cp $d8Jar com.android.tools.r8.D8 --release --lib $androidJar `
    --output (Join-Path $build "dex") $classes 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "d8 failed ($LASTEXITCODE)" }
$dex = Get-ChildItem (Join-Path $build "dex") -Filter *.dex | Select-Object -First 1
if (-not $dex) { throw "d8 produced no dex" }

Write-Host "`n== 5. assemble (resources + classes.dex + assets) =="
# .NET's ZipFile is not resolvable from this host's PowerShell, so assemble with a tiny Java tool.
$toolClasses = Join-Path $build "toolclasses"
New-Item -ItemType Directory -Path $toolClasses -Force | Out-Null
& $javac -encoding UTF-8 -d $toolClasses (Join-Path $root "tools-src\Assemble.java") 2>&1 | ForEach-Object { Write-Host "$_" }
if ($LASTEXITCODE -ne 0) { throw "Assemble.java compile failed ($LASTEXITCODE)" }

$unsigned = Join-Path $build "unsigned.apk"
$extra = @("classes.dex=$($dex.FullName)")
Get-ChildItem (Join-Path $app "assets") -File | ForEach-Object { $extra += "assets/$($_.Name)=$($_.FullName)" }
& $java -cp $toolClasses Assemble $baseApk $unsigned @extra 2>&1 | ForEach-Object { Write-Host "$_" }
if ($LASTEXITCODE -ne 0) { throw "assemble failed ($LASTEXITCODE)" }

Write-Host "`n== 6. zipalign =="
$aligned = Join-Path $build "aligned.apk"
& $zipalign -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed ($LASTEXITCODE)" }

Write-Host "`n== 7. keystore + apksigner =="
New-Item -ItemType Directory -Path $ksDir -Force | Out-Null
if (-not (Test-Path $ks)) {
    Write-Host "no keystore at $ks - generating a new signing key"
    & $keytool -genkeypair -keystore $ks -alias huangguo -keyalg RSA -keysize 2048 `
        -validity 10950 -storepass android -keypass android `
        -dname "CN=HuangGuo ShortDrama, OU=App, O=HuangGuo, L=N/A, ST=N/A, C=CN" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed ($LASTEXITCODE)" }
} else {
    Write-Host "reusing existing signing key: $ks"
}
if (Test-Path $outApk) { Remove-Item $outApk -Force }
& $java -jar $signJar sign `
    --ks $ks --ks-key-alias huangguo --ks-pass pass:android --key-pass pass:android `
    --out $outApk $aligned 2>&1 | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed ($LASTEXITCODE)" }

Write-Host "`n== 8. verify =="
& $java -jar $signJar verify --print-certs $outApk 2>&1 | ForEach-Object { Write-Host "$_" }
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed ($LASTEXITCODE)" }
& $aapt2 dump badging $outApk 2>&1 | Select-String -Pattern "package:|application-label|sdkVersion|targetSdkVersion|launchable" | ForEach-Object { Write-Host $_.Line }

Write-Host "`nAPK: $outApk ($((Get-Item $outApk).Length) bytes)"
