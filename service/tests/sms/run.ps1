$ErrorActionPreference = 'Stop'
$smsTestRoot = $PSScriptRoot
$smsServiceRoot = (Resolve-Path (Join-Path $smsTestRoot '..\..')).Path
$smsClasses = Join-Path $smsTestRoot 'classes'
New-Item -ItemType Directory -Path $smsClasses -Force | Out-Null
$smsJavac = (Get-Command javac).Source
$smsJava = Join-Path (Split-Path $smsJavac) 'java.exe'
& $smsJavac --release 8 -encoding UTF-8 -d $smsClasses `
  (Join-Path $smsServiceRoot 'app\src\main\java\com\smartvowificall\ims\sms\SmsPduCodec.java') `
  (Join-Path $smsTestRoot 'SmsPduCodecTest.java')
if ($LASTEXITCODE -ne 0) { throw 'SMS test compilation failed' }
& $smsJava -cp $smsClasses SmsPduCodecTest
if ($LASTEXITCODE -ne 0) { throw 'SMS codec tests failed' }
