$ErrorActionPreference = "Stop"
$JAVA_HOME = "D:\tools\jdk\zulu17.46.19-ca-jdk17.0.9-win_x64"
$SDK       = "D:\tools\android-sdk"
$BT        = "$SDK\build-tools\34.0.0"
$PLATFORM  = "$SDK\platforms\android-30\android.jar"
$PROJ      = "D:\scripts\wzapp"
$BUILD     = "$PROJ\build"

$env:JAVA_HOME = $JAVA_HOME
$env:PATH = "$JAVA_HOME\bin;$BT;$env:PATH"

Write-Host "== clean =="
Remove-Item $BUILD -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $BUILD, "$BUILD\classes", "$BUILD\dex" | Out-Null

Write-Host "== aapt2 compile =="
& "$BT\aapt2.exe" compile --dir "$PROJ\res" -o "$BUILD\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "== aapt2 link =="
& "$BT\aapt2.exe" link -o "$BUILD\app-unaligned.apk" `
    -I "$PLATFORM" `
    --manifest "$PROJ\AndroidManifest.xml" `
    --min-sdk-version 24 --target-sdk-version 30 `
    --version-code 2 --version-name 0.2.0 `
    "$BUILD\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== javac =="
$srcs = Get-ChildItem "$PROJ\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& "$JAVA_HOME\bin\javac.exe" -source 8 -target 8 -encoding UTF-8 `
    -bootclasspath "$PLATFORM" -d "$BUILD\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "== d8 =="
$classes = Get-ChildItem "$BUILD\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$BT\d8.bat" --lib "$PLATFORM" --min-api 24 --output "$BUILD\dex" $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "== pack apk =="
Copy-Item "$BUILD\app-unaligned.apk" "$BUILD\app-dex.apk" -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$BUILD\app-dex.apk", "Update")
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$BUILD\dex\classes.dex", "classes.dex", "Optimal") | Out-Null
$zip.Dispose()

Write-Host "== zipalign =="
& "$BT\zipalign.exe" -f 4 "$BUILD\app-dex.apk" "$BUILD\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "== sign =="
$ks = "$PROJ\wz.keystore"
if (-not (Test-Path $ks)) {
    & "$JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore $ks -alias wz -keyalg RSA `
        -keysize 2048 -validity 10000 -storepass android -keypass android `
        -dname "CN=wzauto, OU=dev, O=dev, L=NA, ST=NA, C=CN" | Out-Null
}
& "$BT\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$PROJ\wzauto.apk" "$BUILD\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "== done =="
Get-Item "$PROJ\wzauto.apk" | Select-Object Name, @{n='KB'; e={[math]::Round($_.Length/1KB,1)}}
