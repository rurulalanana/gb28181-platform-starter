# 安全重启本平台（只杀本项目这一个 jar 的进程，不动其它 Java 进程）
#
# 用法：
#   .\scripts\restart-local.ps1              # 只重启
#   .\scripts\restart-local.ps1 -Build       # 先打包再重启
#   .\scripts\restart-local.ps1 -Stop        # 只停
#
# 为什么要有这个脚本：
#   早期调试时用的是 `Get-Process java | Stop-Process -Force`，
#   那会杀掉机器上**所有** Java 进程 —— VS Code 的 Java 扩展、
#   IDEA 索引、别的项目在跑的服务全都会被打断。
#   这里改成按命令行里的 jar 文件名精确匹配，只动本项目那一个。

[CmdletBinding()]
param(
    # 先执行 mvn clean package 再重启
    [switch]$Build,
    # 只停不启
    [switch]$Stop,
    # 服务端口
    [int]$Port = 48088,
    <#
      WebRTC 对外通告地址（ZLM 的 rtc.externIP），也是浏览器 ICE 报文要打到的地址。

      【为什么必须单独传】application.yml 里 media-ip 是线上公网 123.57.109.223，
      rtc-extern-ip 留空时回退成它。本机浏览器把 ICE 包发到那个公网地址收不到回包，
      现象是【协商成功但画面全黑】，15 秒后 ZLM 打「接受rtp/rtcp/datachannel超时」
      关掉 transport。FLV 走 HTTP 回源、与 candidate 无关，所以是「FLV 能播、
      WebRTC 不能」。启动日志里会有一行 `ZLM 配置 rtc.externIP=...` 可核对。

      默认 auto = 自动取「默认路由所在网卡」的 IPv4，不用手工查 ipconfig
      （装了 VMware/VirtualBox 的机器上第一条常常是 192.168.80.1 这类虚拟网卡，
      局域网里别的机器到不了）。要固定成某个 IP 就直接传，例如
      -RtcExternIp 192.168.20.138。
    #>
    [string]$RtcExternIp = 'auto',
    <#
      数据库口令。默认取环境变量 GB_DB_PASSWORD。

      【严禁写死在脚本里】本脚本是要入库的，写死等于把生产库口令公开发布。
      早期这里直接写的是明文口令，提交前自查时才被发现。
      本机临时用可以在当前终端先 set：
        $env:GB_DB_PASSWORD='***'
      长期用请写进 `application-local.yml`（已 gitignore），
      或系统环境变量里。
    #>
    [string]$DbPassword = $env:GB_DB_PASSWORD
)

$ErrorActionPreference = 'Stop'

# ---- 路径 ----
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$appDir = Split-Path -Parent $scriptDir          # gb28181-platform-starter
$jarName = 'gb28181-platform-starter-1.0.2.jar'
$jarPath = Join-Path $appDir "target\$jarName"

$env:JAVA_HOME = 'C:\Program Files\BellSoft\LibericaJDK-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

<#
 找出「本项目的那个 java 进程」。

 判据是命令行里出现 target\<jarName>。不要用进程名 java，
 也不要模糊匹配 gb28181 —— 同一台机器上可能有别的国标项目。
#>
function Get-AppProcess {
    # @(...) 包一层：PowerShell 在只匹配到一个对象时会退化成标量，
    # 后续的 .Count / -join 都会失效。
    return @(
        Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
            Where-Object { $_.CommandLine -and $_.CommandLine -like "*$jarName*" }
    )
}

<#
 等端口释放。

 mvn clean 会删 target 目录；如果 java 还占着 jar，Maven 会静默产出一个
 缺 application.yml 的坏包，启动时报 'url' attribute is not specified。
 所以必须先确认进程真的退出了再往下走。
#>
function Wait-ProcessGone {
    param([int]$TimeoutSeconds = 30)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-AppProcess)) { return $true }
        Start-Sleep -Milliseconds 300
    }
    return $false
}

# ---- 停止 ----
$procs = Get-AppProcess
if ($procs) {
    foreach ($p in $procs) {
        Write-Host "停止本项目进程 PID=$($p.ProcessId)" -ForegroundColor Yellow
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    if (-not (Wait-ProcessGone)) {
        throw "进程未在 30 秒内退出，请手动检查 PID=$($procs.ProcessId -join ',')"
    }
} else {
    Write-Host '本项目没有在运行的进程' -ForegroundColor DarkGray
}

if ($Stop) {
    Write-Host '已停止（-Stop）' -ForegroundColor Green
    return
}

# ---- 构建 ----
if ($Build) {
    Push-Location $appDir
    try {
        Write-Host '打包中…' -ForegroundColor Cyan
        # 先删 classes 强制全量：Maven 增量编译只打印 Nothing to compile 时
        # 其实没编译，改过的代码不会进包。
        Remove-Item 'target\classes' -Recurse -Force -ErrorAction SilentlyContinue
        & mvn -o clean package -DskipTests '-Dgpg.skip=true'
        if ($LASTEXITCODE -ne 0) { throw "Maven 打包失败（exit=$LASTEXITCODE）" }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path $jarPath)) {
    throw "找不到 jar：$jarPath（先加 -Build）"
}

# 口令缺失时必须停在这里：application.yml 里没有默认口令，
# 空着启动会以「连不上数据库」的形式失败，排查时会先去查数据库而不是查这个变量。
if ([string]::IsNullOrWhiteSpace($DbPassword)) {
    throw @'
缺少数据库口令。请先设置环境变量再运行，例如：
  $env:GB_DB_PASSWORD='***'
  .\scripts\restart-local.ps1 -Build
或直接传参：.\scripts\restart-local.ps1 -DbPassword '***'
（口令不入库，见 .gitignore 与 docs/部署说明.md）
'@
}

# ---- 启动 ----
Write-Host "启动 :$Port …" -ForegroundColor Cyan
if ($RtcExternIp -eq 'auto') {
    Write-Host 'WebRTC 候选地址使用 auto（自动取默认路由所在网卡的 IPv4）' -ForegroundColor DarkGray
} else {
    Write-Host "WebRTC 候选地址固定为 $RtcExternIp" -ForegroundColor DarkGray
}
$argList = @(
    '-Dfile.encoding=UTF-8'
    '-Dstdout.encoding=UTF-8'
    '-jar', "target\$jarName"
    "--server.port=$Port"
    "--spring.datasource.password=$DbPassword"
    '--dahua.server-mode.enabled=true'
    '--gb28181.api-docs.protect=false'
    '--gb28181.login.captcha-enabled=false'
    # 不加这一项，rtc.externIP 会回退成 media-ip（线上公网 IP），
    # 本机浏览器连不上 → 协商成功但画面全黑。见 param 里的说明。
    "--gb28181.rtc-extern-ip=$RtcExternIp"
)
Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -ArgumentList $argList -WorkingDirectory $appDir -RedirectStandardOutput (Join-Path $appDir 'local-run.log') -RedirectStandardError (Join-Path $appDir 'local-run.err') -WindowStyle Hidden

# ---- 等健康检查 ----
$health = "http://127.0.0.1:$Port/api/v1/health"
$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    try {
        $resp = Invoke-WebRequest -Uri $health -TimeoutSec 3 -UseBasicParsing
        if ($resp.StatusCode -eq 200) {
            Write-Host "已启动：$health" -ForegroundColor Green
            return
        }
    } catch {
        # 还没起来，继续等
    }
}
Write-Warning "健康检查 90 秒未通过，请看 local-run.log / local-run.err"
