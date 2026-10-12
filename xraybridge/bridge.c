#include <jni.h>
#include <stdlib.h>
#include <pthread.h>

static JavaVM *g_vm = NULL;
static jobject g_vpn_service = NULL;
static pthread_mutex_t g_vpn_mutex = PTHREAD_MUTEX_INITIALIZER;

extern char *NeotunPrepare(char *server);
extern char *NeotunSetAssetPath(char *path);
extern char *NeotunInvoke(char *request);
extern void NeotunResetDNS(void);
extern void NeotunFree(char *value);

int neotun_protect_fd(int fd) {
    if (g_vm == NULL) {
        return 0;
    }

    JNIEnv *env = NULL;
    jint attached = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
    int detach = 0;

    if (attached == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) {
            return 0;
        }
        detach = 1;
    } else if (attached != JNI_OK || env == NULL) {
        return 0;
    }

    pthread_mutex_lock(&g_vpn_mutex);
    jobject local_service = NULL;
    if (g_vpn_service != NULL) {
        local_service = (*env)->NewLocalRef(env, g_vpn_service);
    }
    pthread_mutex_unlock(&g_vpn_mutex);

    if (local_service == NULL) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        if (detach) {
            (*g_vm)->DetachCurrentThread(g_vm);
        }
        return 0;
    }

    jclass cls = (*env)->GetObjectClass(env, local_service);
    if (cls == NULL) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        (*env)->DeleteLocalRef(env, local_service);
        if (detach) {
            (*g_vm)->DetachCurrentThread(g_vm);
        }
        return 0;
    }

    jmethodID protect = (*env)->GetMethodID(env, cls, "protect", "(I)Z");
    if (protect == NULL) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        (*env)->DeleteLocalRef(env, cls);
        (*env)->DeleteLocalRef(env, local_service);
        if (detach) {
            (*g_vm)->DetachCurrentThread(g_vm);
        }
        return 0;
    }

    jboolean result = (*env)->CallBooleanMethod(env, local_service, protect, (jint)fd);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        result = JNI_FALSE;
    }

    (*env)->DeleteLocalRef(env, cls);
    (*env)->DeleteLocalRef(env, local_service);

    if (detach) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }

    return result ? 1 : 0;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL
Java_com_neotun_app_NeoTunXrayBridge_nativeInit(
    JNIEnv *env, jclass clazz, jobject vpn_service) {
    (void)clazz;

    pthread_mutex_lock(&g_vpn_mutex);
    if (g_vpn_service != NULL) {
        (*env)->DeleteGlobalRef(env, g_vpn_service);
        g_vpn_service = NULL;
    }
    if (vpn_service != NULL) {
        g_vpn_service = (*env)->NewGlobalRef(env, vpn_service);
    }
    pthread_mutex_unlock(&g_vpn_mutex);
}


JNIEXPORT void JNICALL
Java_com_neotun_app_NeoTunXrayBridge_nativeSetAssetPath(
    JNIEnv *env, jclass clazz, jstring path) {
    (void)clazz;
    if (path == NULL) return;
    const char *path_utf = (*env)->GetStringUTFChars(env, path, NULL);
    if (path_utf == NULL) return;
    char *error = NeotunSetAssetPath((char *)path_utf);
    (*env)->ReleaseStringUTFChars(env, path, path_utf);
    if (error != NULL) {
        jclass exception = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (exception != NULL) (*env)->ThrowNew(env, exception, error);
        NeotunFree(error);
    }
}

JNIEXPORT jstring JNICALL
Java_com_neotun_app_NeoTunXrayBridge_nativePrepare(
    JNIEnv *env, jclass clazz, jstring server) {
    (void)clazz;

    const char *server_utf = server
        ? (*env)->GetStringUTFChars(env, server, NULL)
        : NULL;

    char *error = NeotunPrepare((char *)server_utf);

    if (server_utf != NULL) {
        (*env)->ReleaseStringUTFChars(env, server, server_utf);
    }

    if (error == NULL) {
        return NULL;
    }

    jstring result = (*env)->NewStringUTF(env, error);
    NeotunFree(error);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_neotun_app_NeoTunXrayBridge_nativeInvoke(
    JNIEnv *env, jclass clazz, jstring request) {
    (void)clazz;

    if (request == NULL) {
        return (*env)->NewStringUTF(env, "{\"success\":false,\"error\":\"null request\"}");
    }

    const char *request_utf = (*env)->GetStringUTFChars(env, request, NULL);
    char *response = NeotunInvoke((char *)request_utf);
    (*env)->ReleaseStringUTFChars(env, request, request_utf);

    if (response == NULL) {
        return (*env)->NewStringUTF(env, "{\"success\":false,\"error\":\"empty response\"}");
    }

    jstring result = (*env)->NewStringUTF(env, response);
    NeotunFree(response);
    return result;
}

JNIEXPORT void JNICALL
Java_com_neotun_app_NeoTunXrayBridge_nativeResetDns(
    JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    NeotunResetDNS();
}
