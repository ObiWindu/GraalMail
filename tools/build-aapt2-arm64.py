#!/usr/bin/env python3
"""Repairs the AOSP sources for a modern GCC and continues the aapt2 build.

Every failed translation unit is reported by ninja. For the errors that come
from a missing standard header the header is added to the file and the build
is continued.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path("/home/macewindu/ai-dev/android-sdk-linux-arm64")
BUILD = ROOT / "build" / "build-tools-35.0.2"

HEADERS = {
    "uint8_t": ["#include <cstdint>"],
    "uint16_t": ["#include <cstdint>"],
    "uint32_t": ["#include <cstdint>"],
    "uint64_t": ["#include <cstdint>"],
    "int8_t": ["#include <cstdint>"],
    "int16_t": ["#include <cstdint>"],
    "int32_t": ["#include <cstdint>"],
    "int64_t": ["#include <cstdint>"],
    "size_t": ["#include <cstdint>"],
    "ssize_t": ["#include <cstdint>"],
    "SSIZE_MAX": ["#include <cstdint>", "#include <limits.h>", "#include <sys/types.h>"],
    "SIZE_MAX": ["#include <cstdint>", "#include <limits.h>"],
    "INT64_MAX": ["#include <cstdint>"],
    "ULONG_MAX": ["#include <limits.h>"],
    "LONG_MAX": ["#include <limits.h>"],
    "ULLONG_MAX": ["#include <limits.h>"],
    "INT_MAX": ["#include <limits.h>"],
    "UINT_MAX": ["#include <limits.h>"],
    "INT_MIN": ["#include <limits.h>"],
    "CHAR_BIT": ["#include <climits>"],
    "INT32_MAX": ["#include <cstdint>"],
    "std::string": ["#include <string>"],
    "std::vector": ["#include <vector>"],
    "std::map": ["#include <map>"],
    "std::unique_ptr": ["#include <memory>"],
    "std::shared_ptr": ["#include <memory>"],
    "std::optional": ["#include <optional>"],
    "std::function": ["#include <functional>"],
    "std::move": ["#include <utility>"],
    "std::sort": ["#include <algorithm>"],
    "std::min": ["#include <algorithm>"],
    "std::max": ["#include <algorithm>"],
    "std::clamp": ["#include <algorithm>"],
    "std::swap": ["#include <utility>"],
    "std::memcpy": ["#include <cstring>"],
    "std::strlen": ["#include <cstring>"],
    "std::strcmp": ["#include <cstring>"],
    "std::snprintf": ["#include <cstdio>"],
    "std::abort": ["#include <cstdlib>"],
    "std::free": ["#include <cstdlib>"],
    "std::malloc": ["#include <cstdlib>"],
    "std::atoi": ["#include <cstdlib>"],
    "std::isdigit": ["#include <cctype>"],
    "std::tolower": ["#include <cctype>"],
    "std::toupper": ["#include <cctype>"],
    "std::chrono": ["#include <chrono>"],
    "std::mutex": ["#include <mutex>"],
    "std::thread": ["#include <thread>"],
    "std::atomic": ["#include <atomic>"],
    "std::unordered_map": ["#include <unordered_map>"],
    "std::set": ["#include <set>"],
    "std::array": ["#include <array>"],
    "std::variant": ["#include <variant>"],
    "std::ostream": ["#include <ostream>"],
    "std::ifstream": ["#include <fstream>"],
}

FAILED = re.compile(r"^FAILED: \[code=1\] (\S+)", re.M)
ERROR = re.compile(r"^(?P<file>[^:\n]+\.(?:cpp|cc|c|h)):(\d+):\d+: error: (?P<msg>.*)$", re.M)


def add_include(path: Path, header: str) -> bool:
    text = path.read_text(errors="ignore")
    if header in text:
        return False
    lines = text.splitlines(keepends=True)
    insert_at = 0
    for index, line in enumerate(lines):
        if line.startswith("#include"):
            insert_at = index
            break
    else:
        insert_at = 0
    lines.insert(insert_at, header + "\n")
    path.write_text("".join(lines))
    return True


def main() -> int:
    target = sys.argv[1] if len(sys.argv) > 1 else "aapt2"
    for attempt in range(400):
        result = subprocess.run(
            ["ninja", "-C", str(BUILD), "-j", "12", target],
            capture_output=True, text=True)
        output = result.stdout + result.stderr
        if result.returncode == 0:
            print("build finished")
            return 0
        failures = FAILED.findall(output)
        if not failures:
            print(output[-4000:])
            return 1
        errors = ERROR.findall(output)
        repaired = False
        for file_name, line, message in errors:
            for symbol, headers in HEADERS.items():
                if symbol in message and ("not declared" in message
                                          or "has not been declared" in message
                                          or "is not a member" in message
                                          or "does not name a type" in message):
                    path = Path(file_name)
                    if not path.exists():
                        break
                    for header in headers:
                        if add_include(path, header):
                            print(f"patched {path.name} with {header} for {symbol}")
                            repaired = True
                            break
                    break
        if not repaired:
            print(output[-6000:])
            return 1
    return 1


if __name__ == "__main__":
    sys.exit(main())
