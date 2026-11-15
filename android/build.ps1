$ErrorActionPreference = "Stop"

# JDK 必须是 17：build-tools 34.0.0 的 d8 8.2.2 读到 JDK 21 javac 生成的匿名类 MethodParameters 属性会 NPE。
$ROOT = $PSScriptRoot
$JDK  = "C:\Users\zec_i\apps\jdk17"
$SDK  = "C:\Users\zec_i\apps\android-sdk"
$BT   = "$SDK\build-tools\34.0.0"
$AJAR = "$SDK\platforms\android-34\android.jar"
$OUT  = "$ROOT\build"

$env:JAVA_HOME = $JDK
$env:PATH = "$JDK\bin;$BT;$env:PATH"

Remove-Item -Recurse -Force $OUT -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$OUT\gen", "$OUT\classes", "$OUT\dex" | Out-Null

Write-Host "[1/7] 生成 cookie.txt"
$raw  = (Get-Content "$ROOT\..\.cookie" -Raw).Trim()
$uin  = [regex]::Match($raw, '(^|;\s*)uin=([^;]+)').Groups[2].Value
$skey = [regex]::Match($raw, '(^|;\s*)qm_keyst=([^;]+)').Groups[2].Value
if (-not $uin -or -not $skey) { throw ".cookie 里找不到 uin 或 qm_keyst" }
New-Item -ItemType Directory -Force -Path "$ROOT\assets" | Out-Null
# utf8NoBOM 不带 BOM，不会污染 cookie 第一行
Set-Content -Path "$ROOT\assets\cookie.txt" -Value @($raw, $uin, $skey) -Encoding utf8NoBOM

Write-Host "[2/7] 版本号"
$ver  = Get-Content "$ROOT\version.txt"
$code = [int]$ver[0] + 1
$name = $ver[1].Trim()
Set-Content -Path "$ROOT\version.txt" -Value @($code, $name) -Encoding utf8NoBOM
Write-Host "      versionCode=$code versionName=$name"

Write-Host "[3/7] 编译资源"
& "$BT\aapt2.exe" compile --dir "$ROOT\res" -o "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }

Write-Host "[4/7] 链接资源"
# --replace-version 不能去掉：不加的话 aapt2 只在 manifest 没有版本号时才注入 --version-code
& "$BT\aapt2.exe" link -o "$OUT\base.apk" -I $AJAR `
    --manifest "$ROOT\AndroidManifest.xml" --java "$OUT\gen" `
    -A "$ROOT\assets" --replace-version --version-code $code --version-name $name "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

Write-Host "[5/7] 编译 Java"
$srcs = @()
$srcs += Get-ChildItem -Recurse "$ROOT\src" -Filter *.java | ForEach-Object { $_.FullName }
$srcs += Get-ChildItem -Recurse "$OUT\gen" -Filter *.java | ForEach-Object { $_.FullName }
& "$JDK\bin\javac.exe" -encoding UTF-8 -nowarn -source 8 -target 8 -classpath $AJAR -d "$OUT\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

Write-Host "[6/7] 转 dex 并打包"
$cls = @(Get-ChildItem -Recurse "$OUT\classes" -Filter *.class | ForEach-Object { $_.FullName })
& "$BT\d8.bat" --lib $AJAR --min-api 26 --output "$OUT\dex" $cls
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$OUT\base.apk", 'Update')
$e = $zip.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
$fi = [System.IO.File]::OpenRead("$OUT\dex\classes.dex")
$eo = $e.Open()
$fi.CopyTo($eo)
$eo.Close(); $fi.Close(); $zip.Dispose()

Write-Host "[7/7] 签名"
if (-not (Test-Path "$ROOT\qmix.jks")) {
    & "$JDK\bin\keytool.exe" -genkeypair -keystore "$ROOT\qmix.jks" -alias qmix `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass qmix123 -keypass qmix123 `
        -dname "CN=Acceyuriko, O=Acceyuriko, C=CN" 2>&1 | Out-Null
}
& "$BT\apksigner.bat" sign --ks "$ROOT\qmix.jks" --ks-pass pass:qmix123 `
    --key-pass pass:qmix123 --out "$OUT\qmix.apk" "$OUT\base.apk"
if ($LASTEXITCODE -ne 0) { throw "签名失败" }

Get-Item "$OUT\qmix.apk" | Select-Object Name, @{n = 'KB'; e = { [math]::Round($_.Length / 1KB, 1) } } | Format-Table -AutoSize | Out-String | Write-Host
