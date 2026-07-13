#!/usr/bin/env bash

set -euo pipefail

# FileWeft does not redistribute KingbaseES. This helper obtains the public
# x86_64 Docker archive from the vendor download host, verifies the exact
# bytes observed on 2026-07-14, and loads it only into the current Docker
# daemon for the dedicated integration lane.
readonly image="kingbase_v008r006c009b0014_single_x86:v1"
readonly expected_image_id="sha256:c9e9fdb309b6b18f022e16a8cc4ea91108bf1e609e3ac134e0050a82a01ed5d9"
readonly archive_url="https://kingbase.oss-cn-beijing.aliyuncs.com/KESV8R3/V008R006C009B0014/kdb_x86_64_V008R006C009B0014.tar"
readonly expected_md5="0fd663e7096d1f2e24a7c925f6f6fe52"
readonly expected_sha256="b95e6c39b9a93f3a37354d8f91f78990c99ce9735503210eea14553e92e82595"

if actual_image_id="$(docker image inspect --format '{{.Id}}' "${image}" 2>/dev/null)"; then
  if [[ "${actual_image_id}" != "${expected_image_id}" ]]; then
    echo "Refusing unverified ${image}: expected ${expected_image_id}, got ${actual_image_id}" >&2
    exit 1
  fi
  echo "Using verified local ${image} (${actual_image_id})"
  exit 0
fi

archive="$(mktemp "${TMPDIR:-/tmp}/fileweft-kingbase.XXXXXXXX.tar")"
trap 'rm -f "${archive}"' EXIT

curl \
  --fail \
  --location \
  --retry 3 \
  --retry-all-errors \
  --connect-timeout 20 \
  --user-agent 'Mozilla/5.0 (compatible; FileWeft KingbaseES integration verification)' \
  --referer 'https://www.kingbase.com.cn/download.html' \
  --output "${archive}" \
  "${archive_url}"

printf '%s  %s\n' "${expected_md5}" "${archive}" | md5sum --check --strict
printf '%s  %s\n' "${expected_sha256}" "${archive}" | sha256sum --check --strict
docker load --input "${archive}"

actual_image_id="$(docker image inspect --format '{{.Id}}' "${image}")"
if [[ "${actual_image_id}" != "${expected_image_id}" ]]; then
  echo "Loaded KingbaseES image identity mismatch: expected ${expected_image_id}, got ${actual_image_id}" >&2
  exit 1
fi

echo "Loaded verified ${image} (${actual_image_id})"
