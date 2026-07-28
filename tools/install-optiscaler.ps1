param(
    [string]$TargetDirectory = (
        Join-Path $PSScriptRoot "..\run\config\dlss_fg\optiscaler"
    ),
    [string]$ReleaseTag = "latest"
)

$ErrorActionPreference = "Stop"
$headers = @{ "User-Agent" = "Minecraft-Vulkan-FrameGen-OptiScaler-Installer" }
$repository = "optiscaler/OptiScaler"
$apiUri = if ($ReleaseTag -eq "latest") {
    "https://api.github.com/repos/$repository/releases/latest"
} else {
    "https://api.github.com/repos/$repository/releases/tags/$ReleaseTag"
}

$release = Invoke-RestMethod -Headers $headers -Uri $apiUri
$asset = $release.assets |
    Where-Object { $_.name -match '\.7z$' } |
    Select-Object -First 1
if ($null -eq $asset) {
    throw "The official release does not contain a .7z asset."
}
if ([string]::IsNullOrWhiteSpace($asset.digest) -or
    -not $asset.digest.StartsWith("sha256:")) {
    throw "GitHub did not provide a SHA-256 digest for the release asset."
}

$resolvedTarget = [System.IO.Path]::GetFullPath($TargetDirectory)
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) (
    "dlss-fg-optiscaler-" + [guid]::NewGuid().ToString("N")
)
$resolvedTemporaryRoot = [System.IO.Path]::GetFullPath($temporaryRoot)
$archive = Join-Path $resolvedTemporaryRoot $asset.name
$extracted = Join-Path $resolvedTemporaryRoot "extracted"

try {
    New-Item -ItemType Directory -Path $extracted -Force | Out-Null
    Write-Host "Downloading official OptiScaler $($release.tag_name)..."
    Invoke-WebRequest -Headers $headers -Uri $asset.browser_download_url `
        -OutFile $archive

    $actualHash = (
        (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
    ).ToLowerInvariant()
    $expectedHash = $asset.digest.Substring("sha256:".Length).ToLowerInvariant()
    if ($actualHash -ne $expectedHash) {
        throw "SHA-256 mismatch. Expected $expectedHash, received $actualHash."
    }

    tar -xf $archive -C $extracted
    if (-not (Test-Path -LiteralPath (Join-Path $extracted "OptiScaler.dll"))) {
        throw "The verified archive does not contain OptiScaler.dll."
    }

    New-Item -ItemType Directory -Path $resolvedTarget -Force | Out-Null
    # Keep the optional bridge focused on Vulkan upscaling. The official
    # archive also contains D3D12 frame-generation, XeFG, fake-NVAPI and Nukem
    # components that this integration neither loads nor advertises.
    $requiredEntries = @(
        "OptiScaler.dll",
        "OptiScaler.ini",
        "amd_fidelityfx_vk.dll",
        "amd_fidelityfx_dx12.dll",
        "amd_fidelityfx_upscaler_dx12.dll",
        "libxess.dll",
        "D3D12_Optiscaler",
        "Licenses"
    )
    foreach ($entryName in $requiredEntries) {
        $source = Get-Item -LiteralPath (Join-Path $extracted $entryName) `
            -ErrorAction SilentlyContinue
        if ($null -eq $source) {
            Write-Warning "Optional release entry is missing: $entryName"
            continue
        }
        $destination = Join-Path $resolvedTarget $source.Name
        if ($source.Name -eq "OptiScaler.ini" -and
            (Test-Path -LiteralPath $destination)) {
            Write-Host "Keeping existing OptiScaler.ini."
        } elseif ($source.PSIsContainer) {
            New-Item -ItemType Directory -Path $destination -Force | Out-Null
            Get-ChildItem -LiteralPath $source.FullName -Force | ForEach-Object {
                Copy-Item -LiteralPath $_.FullName -Destination $destination `
                    -Recurse -Force
            }
        } else {
            Copy-Item -LiteralPath $source.FullName -Destination $destination -Force
        }
    }

    Write-Host "Installed OptiScaler $($release.tag_name) to $resolvedTarget"
    Write-Host "Verified SHA-256: $actualHash"
    Write-Host "Enable 'OptiScaler (Restart)' in the mod settings, then restart."
} finally {
    $tempBase = [System.IO.Path]::GetFullPath(
        [System.IO.Path]::GetTempPath()
    )
    if ($resolvedTemporaryRoot.StartsWith(
            $tempBase,
            [System.StringComparison]::OrdinalIgnoreCase
        ) -and (Test-Path -LiteralPath $resolvedTemporaryRoot)) {
        Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force
    }
}
