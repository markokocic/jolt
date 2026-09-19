#!/bin/sh
# Provision the pinned Chez Scheme locally on bionic (Android/Termux).
#
# makes' provisioning assumes glibc twice over, and on bionic neither holds:
#
#   - gcc.mk fetches xPack GCC, a glibc binary the bionic loader cannot exec;
#   - chezscheme.mk runs Chez's `make install`, which hard-links petite and
#     scheme-script to scheme — Android's SELinux policy refuses link() under
#     app data (verified on Termux: EACCES), so the install stops at the
#     first `ln -f`.
#
# So the pinned release is built here with the host compiler and staged by
# hand into the layout make and bin/jolt expect: bin/scheme (with petite and
# scheme-script), lib/csv<version>/<machine> holding the boot files,
# libkernel.a and scheme.h, and the static libz.a / liblz4.a beside the
# kernel so bld-compression-lib bakes compression in rather than falling
# back to dynamic -lz -llz4. LIBS=-liconv is load-bearing: Chez's Linux
# sources compile their iconv support unconditionally and bionic has no
# iconv of its own, so the kernel's libiconv_open/close need the library
# Termux packages separately.
#
# Usage: bionic-provision-chez.sh PREFIX VERSION [CC]
#
#   PREFIX   install prefix, a chezscheme-<version> directory
#   VERSION  Chez release to build (no leading v)
#   CC       C compiler to build with (default: $CC, else cc)
#
# The download and extract are cached in the prefix's parent, matching makes'
# layout: <parent>/cache/csv<version>.tar.gz, <parent>/tmp/csv<version>.

set -eu

prefix=$1
version=$2
cc=${3:-${CC:-cc}}
root=$(dirname "$prefix")
cache=$root/cache
tmp=$root/tmp
tar=$cache/csv$version.tar.gz
src=$tmp/csv$version
url=https://github.com/cisco/ChezScheme/releases/download/v$version/csv$version.tar.gz

if [ -x "$prefix/bin/scheme" ]; then
  echo "bionic-provision-chez: $prefix/bin/scheme is already provisioned"
  exit 0
fi

mkdir -p "$cache" "$tmp"

if [ ! -s "$tar" ]; then
  echo "bionic-provision-chez: downloading csv$version.tar.gz"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --connect-timeout 20 -o "$tar.tmp" "$url"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$tar.tmp" "$url"
  else
    echo "bionic-provision-chez: need curl or wget to fetch $url" >&2
    exit 1
  fi
  mv "$tar.tmp" "$tar"
fi

# Reuse an existing extract: configure and make are incremental, so a retry
# after a failure resumes instead of building the bytecode bootstrap again.
if [ ! -f "$src/Makefile" ]; then
  rm -rf "$src"
  tar -C "$tmp" -xzf "$tar"
fi

jobs=$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)

echo "bionic-provision-chez: building Chez Scheme $version with $cc"
(
  cd "$src"
  ./configure \
    --installprefix="$prefix" \
    --disable-x11 \
    --disable-curses \
    --threads \
    CC="$cc" \
    LIBS=-liconv
  make -j"$jobs"
)

# The workarea directory is named after the machine (tarm64le, ta6le, ...);
# read it from the generated Makefile rather than guessing.
m=$(sed -n 's/^workarea=//p' "$src/Makefile" | head -n1)
if [ -z "$m" ]; then
  echo "bionic-provision-chez: no workarea= in $src/Makefile" >&2
  exit 1
fi
wa=$src/$m
csv=$prefix/lib/csv$version/$m

mkdir -p "$prefix/bin" "$csv"
cp -f "$wa/bin/$m/scheme" "$csv/scheme"
chmod 755 "$csv/scheme"
for f in petite.boot scheme.boot libkernel.a scheme.h; do
  cp -f "$wa/boot/$m/$f" "$csv/$f"
done
cp -f "$wa/zlib/libz.a" "$csv/libz.a"
cp -f "$wa/lz4/lib/liblz4.a" "$csv/liblz4.a"
# Symlinks, not Chez's hard links — the filesystem refuses those.
ln -sf scheme "$csv/petite"
ln -sf "../lib/csv$version/$m/scheme" "$prefix/bin/scheme"
ln -sf "../lib/csv$version/$m/petite" "$prefix/bin/petite"
ln -sf "../lib/csv$version/$m/scheme" "$prefix/bin/scheme-script"

if [ ! -x "$prefix/bin/scheme" ]; then
  echo "bionic-provision-chez: $prefix/bin/scheme is not executable" >&2
  exit 1
fi
echo "bionic-provision-chez: $("$prefix/bin/scheme" --version 2>/dev/null || echo version-unknown) staged at $prefix"
