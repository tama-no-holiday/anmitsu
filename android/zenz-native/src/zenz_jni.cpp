// JNI bridge for dev.astra.ime.oss.zenz.ZenzNative. Strings cross as UTF-8 byte arrays (not modified UTF-8),
// so supplementary characters such as emoji survive. Nothing is logged.
#include <jni.h>

#include <string>
#include <vector>

#include "zenz_scorer.h"

namespace {

std::string bytes(JNIEnv * env, jbyteArray array) {
    if (!array) return {};
    const jsize n = env->GetArrayLength(array);
    std::string s(size_t(n), '\0');
    env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_dev_astra_ime_oss_zenz_ZenzNative_open(JNIEnv * env, jclass, jbyteArray path, jint threads) {
    return reinterpret_cast<jlong>(ZenzScorer::open(bytes(env, path), threads));
}

JNIEXPORT void JNICALL Java_dev_astra_ime_oss_zenz_ZenzNative_close(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<ZenzScorer *>(handle);
}

// Token count of [text] without special tokens; used to check that tokenization is character level.
JNIEXPORT jint JNICALL Java_dev_astra_ime_oss_zenz_ZenzNative_tokenCount(JNIEnv * env, jclass, jlong handle, jbyteArray text) {
    auto * scorer = reinterpret_cast<ZenzScorer *>(handle);
    return scorer ? jint(scorer->tokenize(bytes(env, text), false).size()) : -1;
}

// Returns log P(candidate + </s> | prompt) per candidate, or null on a llama.cpp error.
JNIEXPORT jfloatArray JNICALL Java_dev_astra_ime_oss_zenz_ZenzNative_score(
        JNIEnv * env, jclass, jlong handle, jbyteArray prompt, jobjectArray candidates, jboolean append_end) {
    auto * scorer = reinterpret_cast<ZenzScorer *>(handle);
    if (!scorer) return nullptr;
    const jsize n = env->GetArrayLength(candidates);
    std::vector<std::string> list;
    list.reserve(size_t(n));
    for (jsize i = 0; i < n; i++) {
        auto item = static_cast<jbyteArray>(env->GetObjectArrayElement(candidates, i));
        list.push_back(bytes(env, item));
        env->DeleteLocalRef(item);
    }
    std::vector<float> scores;
    if (!scorer->score(bytes(env, prompt), list, scores, append_end == JNI_TRUE)) return nullptr;
    jfloatArray out = env->NewFloatArray(n);
    env->SetFloatArrayRegion(out, 0, n, scores.data());
    return out;
}

}  // extern "C"
