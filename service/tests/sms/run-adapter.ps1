$ErrorActionPreference = 'Stop'
$smsAdapterRoot = $PSScriptRoot
$smsAdapterService = (Resolve-Path (Join-Path $smsAdapterRoot '..\..')).Path
$smsAdapterClasses = Join-Path $smsAdapterRoot 'adapter-classes'
New-Item -ItemType Directory -Path $smsAdapterClasses -Force | Out-Null
$smsAdapterJavac = (Get-Command javac).Source
$smsAdapterJava = Join-Path (Split-Path $smsAdapterJavac) 'java.exe'
$smsAdapterSources = @(
  (Join-Path $smsAdapterService 'app\src\main\java\com\smartvowificall\ims\NewSms.java'),
  (Join-Path $smsAdapterService 'app\src\main\java\com\smartvowificall\ims\sms\SmsPduCodec.java'),
  (Join-Path $smsAdapterService 'app\src\main\java\com\smartvowificall\ims\engine\EngineState.java'),
  (Join-Path $smsAdapterRoot 'adapter-mocks\android\telephony\SmsManager.java'),
  (Join-Path $smsAdapterRoot 'adapter-mocks\android\telephony\ims\stub\ImsSmsImplBase.java'),
  (Join-Path $smsAdapterRoot 'adapter-mocks\com\smartvowificall\ims\AdapterEnvironment.java'),
  (Join-Path $smsAdapterRoot 'NewSmsAdapterTest.java')
)
& $smsAdapterJavac --release 8 -encoding UTF-8 -Xlint:-options -d $smsAdapterClasses @smsAdapterSources
if ($LASTEXITCODE -ne 0) { throw 'SMS adapter mock compilation failed' }
& $smsAdapterJava -cp $smsAdapterClasses com.smartvowificall.ims.NewSmsAdapterTest
if ($LASTEXITCODE -ne 0) { throw 'SMS adapter mock tests failed' }
