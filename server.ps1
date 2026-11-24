# AntiCheat 테스트 서버 실행 스크립트 (Paper 26.3)
# Written 2026-11-24 by kaneteulin.
#
# 하는 일
#   1. .server 폴더에 Paper 를 받는다 (PaperMC Fill API, SHA-256 확인)
#   2. AntiCheat 플러그인을 설치한다
#        - 이 스크립트 옆에 anti-cheat 소스 폴더가 있으면 직접 빌드 (JDK 25 이상 필요)
#        - 없으면 GitHub Releases 의 최신 AntiCheat.jar 를 받음
#   3. 서버를 실행한다. 서버가 꺼지면 스크립트도 끝난다 (자동 재시작 없음)

# ================= 설정 =================
$Settings = @{
    MinecraftVersion = "26.3"
    Memory           = "16G"
    UsePaperFlags    = $true                      # PaperMC 가 권장하는 JVM 옵션 사용
    ServerFolder     = ".server"
    PluginSource     = "anti-cheat"               # 소스 폴더 이름
    PluginJar        = "AntiCheat.jar"
    ReleaseRepo      = "kaneteulin/anti-cheat"    # GitHub 저장소 (owner/name)
    ExtraPlugins     = @()                        # 함께 받을 플러그인 jar 주소
}
# ========================================

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Agent = @{ "User-Agent" = "anticheat-server/1.0" }
$Fill = "https://fill.papermc.io/v3/projects/paper"

function Write-Step([string]$Text) { Write-Host "==> $Text" -ForegroundColor Cyan }
function Write-Problem([string]$Text) { Write-Host $Text -ForegroundColor Red }

function Get-Sha256([string]$Path) {
    if (!(Test-Path $Path)) { return "" }
    return (Get-FileHash $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

# 서버 파일과 같은 날짜면 건너뛰고, 다르면 새로 받는다
function Save-Url([string]$Url, [string]$Target) {
    $head = Invoke-WebRequest $Url -Method Head -Headers $Agent -UseBasicParsing
    $remoteTime = [datetime]::Parse($head.Headers["Last-Modified"])
    if ((Test-Path $Target) -and (Get-Item $Target).LastWriteTimeUtc -eq $remoteTime.ToUniversalTime()) {
        Write-Host "    최신: $(Split-Path $Target -Leaf)"
        return
    }
    Write-Host "    받는 중: $Url"
    Invoke-WebRequest $Url -OutFile $Target -Headers $Agent -UseBasicParsing
    (Get-Item $Target).LastWriteTimeUtc = $remoteTime.ToUniversalTime()
}

function Get-JavaVersion {
    $text = (cmd /c "java -version 2>&1") -join " "
    if ($text -match 'version "1\.(\d+)') { return [int]$Matches[1] }
    if ($text -match 'version "(\d+)') { return [int]$Matches[1] }
    return 0
}

function Install-Paper($VersionInfo) {
    Write-Step "Paper $($Settings.MinecraftVersion)"
    $build = Invoke-RestMethod "$Fill/versions/$($Settings.MinecraftVersion)/builds/latest" -Headers $Agent
    $file = $build.downloads.'server:default'
    $expected = $file.checksums.sha256.ToLowerInvariant()

    if ((Get-Sha256 "paper.jar") -eq $expected) {
        Write-Host "    최신: build $($build.id)"
        return
    }

    Write-Host "    build $($build.id) 받는 중"
    Invoke-WebRequest $file.url -OutFile "paper.jar" -Headers $Agent -UseBasicParsing
    if ((Get-Sha256 "paper.jar") -ne $expected) {
        Remove-Item "paper.jar" -Force
        throw "paper.jar 체크섬이 맞지 않습니다. 다시 실행해 주세요."
    }
}

function Install-AntiCheat([string]$Root) {
    New-Item -ItemType Directory -Force "plugins" | Out-Null
    $source = Join-Path $Root $Settings.PluginSource
    $target = Join-Path "plugins" $Settings.PluginJar

    if (Test-Path (Join-Path $source "build.ps1")) {
        Write-Step "$($Settings.PluginJar) 빌드 (소스: $source)"

        # 플러그인이 서버 내부 코드를 쓰므로, 빌드하려면 Paper 가 풀어 놓은 서버 jar 가 필요하다
        if (!(Test-Path "versions\$($Settings.MinecraftVersion)")) {
            Write-Host "    서버 jar 준비 중..."
            cmd /c "java -Dpaperclip.patchonly=true -jar paper.jar >nul 2>&1"
        }

        $output = Join-Path $source "build\$($Settings.PluginJar)"
        powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $source "build.ps1") `
            -ServerDir (Get-Location).Path -McVersion $Settings.MinecraftVersion -Output $output
        if ($LASTEXITCODE -ne 0) {
            Write-Problem "    빌드 실패 - 기존 플러그인으로 시작합니다."
            return
        }
        Copy-Item $output $target -Force
        Write-Host "    설치 완료"
        return
    }

    Write-Step "$($Settings.PluginJar) (GitHub Releases)"
    try {
        Save-Url "https://github.com/$($Settings.ReleaseRepo)/releases/latest/download/$($Settings.PluginJar)" $target
    } catch {
        Write-Problem "    받지 못했습니다: $($_.Exception.Message)"
    }
}

# ---------------- 실행 ----------------
$Root = $PSScriptRoot
$ServerDir = Join-Path $Root $Settings.ServerFolder
New-Item -ItemType Directory -Force $ServerDir | Out-Null
Push-Location $ServerDir

try {
    $host.UI.RawUI.WindowTitle = "anti-cheat - paper $($Settings.MinecraftVersion)"

    if (!(Test-Path "eula.txt")) { Set-Content "eula.txt" "eula=true" -Encoding ASCII }

    $info = (Invoke-RestMethod "$Fill/versions/$($Settings.MinecraftVersion)" -Headers $Agent).version
    $needJava = [int]$info.java.version.minimum
    $haveJava = Get-JavaVersion
    if ($haveJava -lt $needJava) {
        Write-Problem "Java $needJava 이상이 필요합니다 (현재: $haveJava). https://adoptium.net/ 에서 설치하세요."
        return
    }

    Install-Paper $info
    Install-AntiCheat $Root

    foreach ($url in $Settings.ExtraPlugins) {
        Write-Step "추가 플러그인"
        try { Save-Url $url (Join-Path "plugins" ([IO.Path]::GetFileName(([uri]$url).AbsolutePath))) }
        catch { Write-Problem "    받지 못했습니다: $url" }
    }

    $javaArgs = @("-Xms$($Settings.Memory)", "-Xmx$($Settings.Memory)")
    if ($Settings.UsePaperFlags -and $info.java.flags.recommended) { $javaArgs += $info.java.flags.recommended }
    $javaArgs += @("-jar", "paper.jar", "--nogui")

    Write-Step "서버 시작"
    $ErrorActionPreference = "Continue"
    java @javaArgs
    $code = $LASTEXITCODE

    Write-Host ""
    if ($code -eq 0) {
        Write-Host "서버가 종료되었습니다."
    } else {
        Write-Problem "서버가 비정상 종료되었습니다 (종료 코드 $code). 다시 켜려면 server.bat 을 실행하세요."
    }
}
catch {
    Write-Problem "오류: $($_.Exception.Message)"
}
finally {
    Pop-Location
}
