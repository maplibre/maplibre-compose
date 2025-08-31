#pragma once

#include <stdexcept>

namespace maplibre_jni {

inline void check(bool condition, const char* message) {
  if (!condition) throw std::runtime_error(message);
}

}  // namespace maplibre_jni
