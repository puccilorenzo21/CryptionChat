#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define TAG "JNI_Whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_lollo1344_cryptionchat_WhisperLib_init(JNIEnv *env, jobject thiz, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    struct whisper_context *ctx = whisper_init_from_file(path);
    env->ReleaseStringUTFChars(model_path, path);

    if (ctx == nullptr) {
        LOGE("Failed to initialize whisper context");
        return 0;
    }
    LOGI("Whisper context initialized");
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_com_lollo1344_cryptionchat_WhisperLib_free(JNIEnv *env, jobject thiz, jlong context_ptr) {
    struct whisper_context *ctx = (struct whisper_context *) context_ptr;
    if (ctx != nullptr) {
        whisper_free(ctx);
        LOGI("Whisper context freed");
    }
}

JNIEXPORT jstring JNICALL
Java_com_lollo1344_cryptionchat_WhisperLib_transcribe(JNIEnv *env, jobject thiz, jlong context_ptr, jfloatArray samples) {
    struct whisper_context *ctx = (struct whisper_context *) context_ptr;
    if (ctx == nullptr) return env->NewStringUTF("Error: Context is null");

    jfloat *pcm_data = env->GetFloatArrayElements(samples, nullptr);
    jsize data_len = env->GetArrayLength(samples);

    LOGI("Starting transcription with %d samples", data_len);

    // PARAMETRI AD ALTA VELOCITA' PER MESSIAGGI VOCALI BREVI
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    wparams.print_realtime   = false;
    wparams.print_progress   = false;
    wparams.print_timestamps = false;
    wparams.print_special    = false;
    wparams.translate        = false;

    wparams.language         = "it"; // Forziamo l'Italiano (salta la ricerca lingua)
    wparams.n_threads        = 4;    // 4 thread (core veloci dell'S24)

    // NUOVE OTTIMIZZAZIONI VELOCITA':
    wparams.single_segment   = true; // Tratta il vocale come segmento unico
    wparams.no_context       = true; // Disattiva il calcolo del contesto storico
    wparams.greedy.best_of   = 1;    // Decodifica rapida senza campionamenti multipli

    if (whisper_full(ctx, wparams, pcm_data, data_len) != 0) {
        LOGE("Whisper full failed");
        env->ReleaseFloatArrayElements(samples, pcm_data, JNI_ABORT);
        return env->NewStringUTF("Error: Transcription failed");
    }

    std::string result;
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        result += text;
    }

    env->ReleaseFloatArrayElements(samples, pcm_data, JNI_ABORT);
    return env->NewStringUTF(result.c_str());
}

}