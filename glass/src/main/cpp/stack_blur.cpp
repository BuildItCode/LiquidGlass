#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <limits>

namespace {
// NDK Clang lowers these four independent channel operations to NEON/SSE where
// supported by the target ABI, and preserves the same unsigned integer results.
using Channels = uint32_t __attribute__((ext_vector_type(4)));

Channels unpack(uint32_t pixel) {
    return {pixel & 255u, (pixel >> 8) & 255u, (pixel >> 16) & 255u, pixel >> 24};
}

uint32_t pack(const Channels& sum, uint32_t multiplier, int shift) {
    // Unsigned multiply/shift reproduces Kotlin Int overflow followed by ushr.
    const Channels normalized = (sum * multiplier) >> shift;
    return normalized[0] | (normalized[1] << 8) |
           (normalized[2] << 16) | (normalized[3] << 24);
}

void blurLine(const uint32_t* source, uint32_t* destination, int length, int sourceStride, int destinationStride,
              int radius, uint32_t multiplier, int shift) {
    std::array<Channels, 509> ring;
    Channels sum{}, incoming{}, outgoing{};
    const int diameter = radius * 2 + 1;
    const int r1 = radius + 1;
    for (int offset = -radius; offset <= radius; ++offset) {
        const auto pixel = unpack(source[std::clamp(offset, 0, length - 1) * sourceStride]);
        ring[offset + radius] = pixel;
        const uint32_t weight = r1 - (offset < 0 ? -offset : offset);
        sum += pixel * weight;
        if (offset > 0) incoming += pixel; else outgoing += pixel;
    }
    int pointer = radius;
    for (int index = 0; index < length; ++index) {
        destination[index * destinationStride] = pack(sum, multiplier, shift);
        int start = pointer - radius;
        if (start < 0) start += diameter;
        const auto removed = ring[start];
        const int incomingIndex = index < length - r1 ? index + r1 : length - 1;
        const auto added = unpack(source[incomingIndex * sourceStride]);
        ring[start] = added;
        if (++pointer == diameter) pointer = 0;
        const auto next = ring[pointer];
        sum -= outgoing;
        outgoing -= removed;
        incoming += added;
        sum += incoming;
        outgoing += next;
        incoming -= next;
    }
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_builditcode_glass_NativeStackBlur_blur(
    JNIEnv* env, jobject, jintArray pixels, jintArray scratch,
    jint width, jint height, jint radius, jint multiplier, jint shift) {
    const int64_t count = static_cast<int64_t>(width) * height;
    if (!pixels || !scratch || env->IsSameObject(pixels, scratch) ||
        width <= 0 || height <= 0 || count > std::numeric_limits<jint>::max() ||
        radius < 1 || radius > 254 || multiplier < 1 || multiplier > 512 ||
        shift < 1 || shift > 31 || env->GetArrayLength(pixels) < count ||
        env->GetArrayLength(scratch) < count) return JNI_FALSE;
    const uint32_t maximumChannel =
        (255u * (radius + 1) * (radius + 1) * multiplier) >> shift;
    if (maximumChannel > 255u) return JNI_FALSE;

    // Ordinary JNI array access lets ART run GC while the worker computes. No
    // critical/pinned GC section, native heap cache, or cross-worker shared state.
    jint* input = env->GetIntArrayElements(pixels, nullptr);
    if (!input) return JNI_FALSE;
    jint* temporary = env->GetIntArrayElements(scratch, nullptr);
    if (!temporary) {
        env->ReleaseIntArrayElements(pixels, input, JNI_ABORT);
        return JNI_FALSE;
    }
    auto* data = reinterpret_cast<uint32_t*>(input);
    auto* intermediate = reinterpret_cast<uint32_t*>(temporary);
    for (int row = 0; row < height; ++row) {
        blurLine(data + row * width, intermediate + row * width,
                 width, 1, 1, radius, multiplier, shift);
    }
    for (int column = 0; column < width; ++column) {
        blurLine(intermediate + column, data + column,
                 height, width, width, radius, multiplier, shift);
    }
    env->ReleaseIntArrayElements(scratch, temporary, JNI_ABORT);
    env->ReleaseIntArrayElements(pixels, input, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_builditcode_glass_NativeStackBlur_blurOpaque(
    JNIEnv* env, jobject, jobject bitmap, jintArray scratch,
    jint radius, jint multiplier, jint shift) {
    if (!bitmap || !scratch || radius < 1 || radius > 254 || multiplier < 1 ||
        multiplier > 512 || shift < 1 || shift > 31) return JNI_FALSE;
    const uint32_t maximumChannel =
        (255u * (radius + 1) * (radius + 1) * multiplier) >> shift;
    if (maximumChannel > 255u) return JNI_FALSE;
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0 ||
        info.stride % 4 != 0 || info.stride < static_cast<uint64_t>(info.width) * 4 ||
        static_cast<uint64_t>(info.height) * (info.stride / 4) > std::numeric_limits<jint>::max() ||
        env->GetArrayLength(scratch) < static_cast<int64_t>(info.width) * info.height) return JNI_FALSE;
    void* address = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &address) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    auto* data = static_cast<uint32_t*>(address);
    const int width = info.width, height = info.height, pitch = info.stride / 4;
    // For opaque sRGB, premultiplied RGBA bytes and getPixels() channels are
    // identical. Any translucent pixel takes the conversion-preserving array path.
    for (int row = 0; row < height; ++row) {
        for (int column = 0; column < width; ++column) {
            if ((data[row * pitch + column] >> 24) != 255u) {
                AndroidBitmap_unlockPixels(env, bitmap);
                return JNI_FALSE;
            }
        }
    }
    jint* temporary = env->GetIntArrayElements(scratch, nullptr);
    if (!temporary) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return JNI_FALSE;
    }
    auto* intermediate = reinterpret_cast<uint32_t*>(temporary);
    for (int row = 0; row < height; ++row) {
        blurLine(data + row * pitch, intermediate + row * width,
                 width, 1, 1, radius, multiplier, shift);
    }
    for (int column = 0; column < width; ++column) {
        blurLine(intermediate + column, data + column,
                 height, width, pitch, radius, multiplier, shift);
    }
    env->ReleaseIntArrayElements(scratch, temporary, JNI_ABORT);
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}
