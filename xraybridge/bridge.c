#include <jni.h>
#include <stdlib.h>

static JavaVM *g_vm = NULL;
static jobject g_vpn_service = NULL;

extern char *NeotunPrepare(char *server);
extern char *NeotunSetAssetPath(char *path);
extern char *NeotunInvoke(char *request);
extern void NeotunResetDNS(void);
extern void NeotunFree(char *value);

int neotun_protect_fd(int fd) {
    if (g_vm == NULL || g_vpn_service == NULL) {
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
    } else if (attached != JNI_OK) {
        return 0;
    }

    jclass cls = (*env)->GetObjectClass(env, g_vpn_service);
    if (cls == NULL) {
        if (detach) (*g_vm)->DetachCurrentThread(g_vm);
        return 0;
    }

    jmethodID protect = (*env)->GetMethodID(env, cls, "protect", "(I)Z");
    if (protect == NULL) {
        (*env)->DeleteLocalRef(env, cls);
        if (detach) (*g_vm)->DetachCurrentThread(g_vm);
        return 0;
    }

    jboolean result = (*env)->CallBooleanMethod(env, g_vpn_service, protect, (jint)fd);
    (*env)->DeleteLocalRef(env, cls);

    if (detach) (*g_vm)->DetachCurrentThread(g_vm);
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

    if (g_vpn_service != NULL) {
        (*env)->DeleteGlobalRef(env, g_vpn_service);
        g_vpn_service = NULL;
    }
    if (vpn_service != NULL) {
        g_vpn_service = (*env)->NewGlobalRef(env, vpn_service);
    }
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
