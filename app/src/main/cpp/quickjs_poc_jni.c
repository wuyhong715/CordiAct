#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "quickjs.h"

#define QJS_POC_MEMORY_LIMIT_BYTES (8U * 1024U * 1024U)
#define QJS_POC_STACK_LIMIT_BYTES (256U * 1024U)
#define QJS_POC_MAX_SOURCE_BYTES (16U * 1024U)

static int64_t boottime_nanos(void) {
    struct timespec now;
    if (clock_gettime(CLOCK_BOOTTIME, &now) != 0) {
        return INT64_MAX;
    }
    return (int64_t) now.tv_sec * 1000000000LL + (int64_t) now.tv_nsec;
}

static int interrupt_at_deadline(JSRuntime *runtime, void *opaque) {
    (void) runtime;
    const int64_t deadline_nanos = *(const int64_t *) opaque;
    return boottime_nanos() >= deadline_nanos;
}

static jstring error_string(JNIEnv *env, const char *message) {
    char output[512];
    const char *detail = (message != NULL) ? message : "engine-error";
    (void) snprintf(output, sizeof(output), "ERROR:%s", detail);
    return (*env)->NewStringUTF(env, output);
}

JNIEXPORT jstring JNICALL
Java_com_cordiact_app_sandbox_SandboxJsPocService_nativeEvaluate(
        JNIEnv *env, jobject service, jbyteArray source, jlong deadline_nanos) {
    (void) service;
    if (source == NULL || deadline_nanos <= 0) {
        return error_string(env, "invalid-native-input");
    }

    const jsize source_length = (*env)->GetArrayLength(env, source);
    if (source_length <= 0 || source_length > (jsize) QJS_POC_MAX_SOURCE_BYTES) {
        return error_string(env, "source-size-out-of-range");
    }

    jbyte *source_bytes = (*env)->GetByteArrayElements(env, source, NULL);
    if (source_bytes == NULL) {
        return error_string(env, "source-unavailable");
    }

    JSRuntime *runtime = JS_NewRuntime();
    if (runtime == NULL) {
        (*env)->ReleaseByteArrayElements(env, source, source_bytes, JNI_ABORT);
        return error_string(env, "runtime-allocation-failed");
    }

    /* These bounds cover QuickJS allocator/recursion use, not the whole Android process. */
    JS_SetMemoryLimit(runtime, QJS_POC_MEMORY_LIMIT_BYTES);
    JS_SetMaxStackSize(runtime, QJS_POC_STACK_LIMIT_BYTES);
    const int64_t deadline_nanos_64 = (int64_t) deadline_nanos;
    JS_SetInterruptHandler(runtime, interrupt_at_deadline, (void *) &deadline_nanos_64);
    /* No module normalizer/loader is installed; no filesystem or network loader exists. */
    JS_SetModuleLoaderFunc(runtime, NULL, NULL, NULL);

    JSContext *context = JS_NewContext(runtime);
    if (context == NULL) {
        (*env)->ReleaseByteArrayElements(env, source, source_bytes, JNI_ABORT);
        JS_FreeRuntime(runtime);
        return error_string(env, "context-allocation-failed");
    }

    JSValue global_object = JS_GetGlobalObject(context);
    int property_result = JS_SetPropertyStr(context, global_object, "eval", JS_UNDEFINED);
    if (property_result >= 0) {
        property_result = JS_SetPropertyStr(context, global_object, "Function", JS_UNDEFINED);
    }
    JS_FreeValue(context, global_object);

    JSValue result;
    if (property_result < 0) {
        result = JS_EXCEPTION;
    } else {
        /* JNI byte-array storage has no guaranteed sentinel; the parser can read
         * at the end of input. Make a bounded terminated copy and keep its exact
         * byte length for JS_Eval, without changing the signed resource bytes. */
        char *terminated_source = (char *) malloc((size_t) source_length + 1U);
        if (terminated_source == NULL) {
            (*env)->ReleaseByteArrayElements(env, source, source_bytes, JNI_ABORT);
            JS_FreeContext(context);
            JS_FreeRuntime(runtime);
            return error_string(env, "source-buffer-allocation-failed");
        }
        memcpy(terminated_source, source_bytes, (size_t) source_length);
        terminated_source[source_length] = 0;
        result = JS_Eval(context,
                         terminated_source,
                         (size_t) source_length,
                         "<signed-apk-fixed-script>",
                         JS_EVAL_TYPE_GLOBAL);
        free(terminated_source);
    }
    (*env)->ReleaseByteArrayElements(env, source, source_bytes, JNI_ABORT);

    jstring java_result;
    if (JS_IsException(result)) {
        JSValue exception = JS_GetException(context);
        const char *message = JS_ToCString(context, exception);
        java_result = error_string(env, message);
        if (message != NULL) {
            JS_FreeCString(context, message);
        }
        JS_FreeValue(context, exception);
    } else {
        const char *value = JS_ToCString(context, result);
        java_result = (value != NULL) ? (*env)->NewStringUTF(env, value)
                                      : error_string(env, "result-conversion-failed");
        if (value != NULL) {
            JS_FreeCString(context, value);
        }
    }

    JS_FreeValue(context, result);
    JS_FreeContext(context);
    JS_FreeRuntime(runtime);
    return java_result;
}
