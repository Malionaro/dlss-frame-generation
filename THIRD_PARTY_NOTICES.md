# Third-party notices

## NVIDIA Streamline 2.12

This project includes NVIDIA Streamline 2.12 runtime components for DLSS Super
Resolution, DLSS Frame Generation, DLSS Ray Reconstruction, Reflex and Vulkan
interposition. The distribution JAR stores Streamline's license,
third-party notices, NGX DLSS license and Reflex license under
`META-INF/licenses/nvidia-streamline/`. The runtime binaries remain subject to
those bundled terms.

## AMD FidelityFX SDK

This project includes the signed `amd_fidelityfx_vk.dll` distributed in the
verified OptiScaler v0.9.4 runtime bundle and the corresponding public FFX API
headers. They expose the FidelityFX Vulkan upscaling and FSR 3.1
frame-generation proxy-swapchain API. No unverified upstream SDK tag is claimed
for this binary.

The collected runtime also contains generic D3D12/upscaler components, but does
not contain `amd_fidelityfx_framegeneration_dx12.dll`, `libxess_fg.dll` or
`libxell.dll`. This project does not redistribute or claim AMD FSR 4 Frame
Generation or Intel XeSS Frame Generation/XeLL provider binaries.

Copyright (C) 2024 Advanced Micro Devices, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Bundled OptiScaler interoperability

The mod contains the unmodified OptiScaler v0.9.4 runtime and uses the public
FidelityFX API as an interoperability boundary for
[OptiScaler](https://github.com/optiscaler/OptiScaler). OptiScaler is licensed
under GPL-3.0 and remains a separate work collected in the same distribution
archive. Its full GPLv3 text, exact upstream commit, SHA-256 manifest and
corresponding-source directions are stored under
`META-INF/optiscaler/v0.9.4/`. Dependency licenses are stored beside the
runtime under `optiscaler/windows/Licenses/`.

OptiScaler warns against use in multiplayer/anti-cheat environments. This mod
loads the bundled bridge only on a detected AMD or Intel Vulkan device.
