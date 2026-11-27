#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <time.h>

#include "quickjs.h"

#define MAX_SOURCE_BYTES (16U * 1024U)
#define MAX_TEXT_BYTES (4U * 1024U)

static int expired(JSRuntime *runtime, void *opaque) {
    (void) runtime;
    struct timespec now;
    if (clock_gettime(CLOCK_BOOTTIME, &now) != 0) return 1;
    const int64_t nanos = (int64_t) now.tv_sec * INT64_C(1000000000) + now.tv_nsec;
    return nanos >= *(const int64_t *) opaque;
}

JNIEXPORT jbyteArray JNICALL
Java_com_cordiact_app_sandbox_PackageScriptWorkerService_nativeEvaluate(
        JNIEnv *env, jobject service, jbyteArray source, jbyteArray input, jlong deadline) {
    (void) service;
    if (!source || !input || deadline <= 0) return NULL;
    const jsize source_size = (*env)->GetArrayLength(env, source);
    const jsize input_size = (*env)->GetArrayLength(env, input);
    if (source_size <= 0 || source_size > (jsize) MAX_SOURCE_BYTES || input_size > (jsize) MAX_TEXT_BYTES) return NULL;

    char script[MAX_SOURCE_BYTES + 1];
    char text[MAX_TEXT_BYTES];
    (*env)->GetByteArrayRegion(env, source, 0, source_size, (jbyte *) script);
    (*env)->GetByteArrayRegion(env, input, 0, input_size, (jbyte *) text);
    if ((*env)->ExceptionCheck(env)) return NULL;
    script[source_size] = '\0';

    int64_t deadline_nanos = (int64_t) deadline;
    if (expired(NULL, &deadline_nanos)) return NULL;
    JSRuntime *runtime = JS_NewRuntime();
    if (!runtime) return NULL;
    JS_SetMemoryLimit(runtime, 8U * 1024U * 1024U);
    JS_SetMaxStackSize(runtime, 256U * 1024U);
    JS_SetInterruptHandler(runtime, expired, &deadline_nanos);
    JS_SetModuleLoaderFunc(runtime, NULL, NULL, NULL);

    JSContext *context = JS_NewContext(runtime);
    if (!context) {
        JS_FreeRuntime(runtime);
        return NULL;
    }

    jbyteArray result = NULL;
    JSValue function = JS_Eval(context, script, (size_t) source_size, "<package-script>", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsFunction(context, function)) {
        JSValue argument = JS_NewStringLen(context, text, (size_t) input_size);
        if (!JS_IsException(argument)) {
            JSValue value = JS_Call(context, function, JS_UNDEFINED, 1, &argument);
            if (JS_IsString(value) && !expired(runtime, &deadline_nanos)) {
                size_t size = 0;
                const char *bytes = JS_ToCStringLen(context, &size, value);
                if (bytes && size <= MAX_TEXT_BYTES) {
                    result = (*env)->NewByteArray(env, (jsize) size);
                    if (result) (*env)->SetByteArrayRegion(env, result, 0, (jsize) size, (const jbyte *) bytes);
                }
                if (bytes) JS_FreeCString(context, bytes);
            }
            JS_FreeValue(context, value);
        }
        JS_FreeValue(context, argument);
    }
    JS_FreeValue(context, function);
    JS_FreeValue(context, JS_GetException(context));
    JS_FreeContext(context);
    JS_FreeRuntime(runtime);
    return result;
}
