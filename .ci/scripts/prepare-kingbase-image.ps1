$ErrorActionPreference = "Stop"

# Windows companion to prepare-kingbase-image.sh. FileWeft does not
# redistribute KingbaseES; the archive stays on the vendor host and is loaded
# only into the current Docker daemon after all identities match.
$image = "kingbase_v008r006c009b0014_single_x86:v1"
$expectedImageId = "sha256:c9e9fdb309b6b18f022e16a8cc4ea91108bf1e609e3ac134e0050a82a01ed5d9"
$archiveUrl = "https://kingbase.oss-cn-beijing.aliyuncs.com/KESV8R3/V008R006C009B0014/kdb_x86_64_V008R006C009B0014.tar"
$expectedMd5 = "0FD663E7096D1F2E24A7C925F6F6FE52"
$expectedSha256 = "B95E6C39B9A93F3A37354D8F91F78990C99CE9735503210EEA14553E92E82595"

$actualImageId = & docker image inspect --format "{{.Id}}" $image 2>$null
if ($LASTEXITCODE -eq 0) {
    if ($actualImageId.Trim() -ne $expectedImageId) {
        throw "Refusing unverified ${image}: expected $expectedImageId, got $actualImageId"
    }
    Write-Output "Using verified local $image ($expectedImageId)"
    exit 0
}

$archive = Join-Path ([System.IO.Path]::GetTempPath()) ("fileweft-kingbase-{0}.tar" -f [guid]::NewGuid())
try {
    & curl.exe `
        --fail `
        --location `
        --retry 3 `
        --retry-all-errors `
        --connect-timeout 20 `
        --user-agent "Mozilla/5.0 (compatible; FileWeft KingbaseES integration verification)" `
        --referer "https://www.kingbase.com.cn/download.html" `
        --output $archive `
        $archiveUrl
    if ($LASTEXITCODE -ne 0) {
        throw "KingbaseES archive download failed with exit code $LASTEXITCODE"
    }

    $actualMd5 = (Get-FileHash -Algorithm MD5 -LiteralPath $archive).Hash
    if ($actualMd5 -ne $expectedMd5) {
        throw "KingbaseES archive MD5 mismatch: expected $expectedMd5, got $actualMd5"
    }
    $actualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash
    if ($actualSha256 -ne $expectedSha256) {
        throw "KingbaseES archive SHA-256 mismatch: expected $expectedSha256, got $actualSha256"
    }

    & docker load --input $archive
    if ($LASTEXITCODE -ne 0) {
        throw "docker load failed with exit code $LASTEXITCODE"
    }

    $actualImageId = (& docker image inspect --format "{{.Id}}" $image).Trim()
    if ($LASTEXITCODE -ne 0 -or $actualImageId -ne $expectedImageId) {
        throw "Loaded KingbaseES image identity mismatch: expected $expectedImageId, got $actualImageId"
    }
    Write-Output "Loaded verified $image ($actualImageId)"
}
finally {
    if (Test-Path -LiteralPath $archive) {
        Remove-Item -LiteralPath $archive -Force
    }
}
