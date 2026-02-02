#include "VideoRendererContext.h"
#include "Log.h"

VideoRendererContext::jni_fields_t VideoRendererContext::jni_fields = {nullptr};

VideoRendererContext::VideoRendererContext(int type) {
    m_pVideoRenderer = VideoRenderer::create(type);
}

VideoRendererContext::~VideoRendererContext() = default;

void VideoRendererContext::init(ANativeWindow *window, AAssetManager *assetManager, size_t width, size_t height) {
    m_pVideoRenderer->init(window, assetManager, width, height);
}

void VideoRendererContext::render() {
    m_pVideoRenderer->render();
}

void VideoRendererContext::draw(uint8_t *buffer, size_t length, size_t width, size_t height,
                                float rotation, bool mirror) {
    m_pVideoRenderer->draw(buffer, length, width, height, rotation, mirror);
}

void VideoRendererContext::setParameters(uint32_t params) {
    m_pVideoRenderer->setParameters(params);
}

uint32_t VideoRendererContext::getParameters() {
    return m_pVideoRenderer->getParameters();
}

void VideoRendererContext::setPortraitMode(bool enable) {
    m_pVideoRenderer->setPortraitMode(enable);
}

void VideoRendererContext::setBlurStrength(float strength) {
    m_pVideoRenderer->setBlurStrength(strength);
}

void VideoRendererContext::setFilter(int filterId) {
    m_pVideoRenderer->setFilter(filterId);
}

void VideoRendererContext::updateDepthData(uint8_t *data, size_t width, size_t height) {
    m_pVideoRenderer->updateDepthData(data, width, height);
}

void VideoRendererContext::setQualityParams(int samples) {
    m_pVideoRenderer->setQualityParams(samples);
}

void VideoRendererContext::captureNextFrame(JNIEnv *env, jobject callback) {
    if (!m_pVideoRenderer) return;

    jobject callbackGlobal = env->NewGlobalRef(callback);
    JavaVM* jvm;
    env->GetJavaVM(&jvm);

    m_pVideoRenderer->captureNextFrame([jvm, callbackGlobal](uint8_t* data, int width, int height) {
        JNIEnv* env;
        bool needsDetach = false;
        int getEnvStat = jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (getEnvStat == JNI_EDETACHED) {
            if (jvm->AttachCurrentThread(&env, nullptr) != 0) {
                return;
            }
            needsDetach = true;
        } else if (getEnvStat == JNI_EVERSION) {
            return;
        }

        jclass callbackClass = env->GetObjectClass(callbackGlobal);
        jmethodID onCaptureMethod = env->GetMethodID(callbackClass, "onCapture", "([BII)V");
        if (onCaptureMethod) {
            jbyteArray byteArray = env->NewByteArray(width * height * 4);
            env->SetByteArrayRegion(byteArray, 0, width * height * 4, (jbyte*)data);
            env->CallVoidMethod(callbackGlobal, onCaptureMethod, byteArray, width, height);
            env->DeleteLocalRef(byteArray);
        }

        env->DeleteGlobalRef(callbackGlobal);

        if (needsDetach) {
            jvm->DetachCurrentThread();
        }
    });
}

void VideoRendererContext::renderStill(JNIEnv *env, jbyteArray yuv, jbyteArray mask, jint width, jint height, jobject callback) {
    if (!m_pVideoRenderer) return;

    jobject callbackGlobal = env->NewGlobalRef(callback);

    // Copy input buffers to native heap because JNI pointers become invalid after this call returns,
    // and the renderer might execute async or need stable pointers.
    // However, for this one-shot synchronous-like behavior (but executed on render thread/queue),
    // it's safer to copy.
    jbyte *yuvBytes = env->GetByteArrayElements(yuv, nullptr);
    jbyte *maskBytes = mask ? env->GetByteArrayElements(mask, nullptr) : nullptr;

    size_t yuvSize = env->GetArrayLength(yuv);
    size_t maskSize = mask ? env->GetArrayLength(mask) : 0;

    std::vector<uint8_t> yuvVec(yuvBytes, yuvBytes + yuvSize);
    std::vector<uint8_t> maskVec;
    if (maskBytes) maskVec.assign(maskBytes, maskBytes + maskSize);

    env->ReleaseByteArrayElements(yuv, yuvBytes, 0);
    if (maskBytes) env->ReleaseByteArrayElements(mask, maskBytes, 0);

    JavaVM* jvm;
    env->GetJavaVM(&jvm);

    m_pVideoRenderer->renderStill(yuvVec.data(), maskVec.empty() ? nullptr : maskVec.data(), width, height, [jvm, callbackGlobal, yuvVec, maskVec](uint8_t* data, int w, int h) {
        JNIEnv* env;
        bool needsDetach = false;
        int getEnvStat = jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (getEnvStat == JNI_EDETACHED) {
            if (jvm->AttachCurrentThread(&env, nullptr) != 0) return;
            needsDetach = true;
        }

        jclass callbackClass = env->GetObjectClass(callbackGlobal);
        jmethodID onCaptureMethod = env->GetMethodID(callbackClass, "onCapture", "([BII)V");
        if (onCaptureMethod) {
            jbyteArray byteArray = env->NewByteArray(w * h * 4);
            env->SetByteArrayRegion(byteArray, 0, w * h * 4, (jbyte*)data);
            env->CallVoidMethod(callbackGlobal, onCaptureMethod, byteArray, w, h);
            env->DeleteLocalRef(byteArray);
        }
        env->DeleteGlobalRef(callbackGlobal);
        if (needsDetach) jvm->DetachCurrentThread();
    });
}

void VideoRendererContext::createContext(JNIEnv *env, jobject obj, jint type) {
    auto *context = new VideoRendererContext(type);

    storeContext(env, obj, context);
}

void VideoRendererContext::storeContext(JNIEnv *env, jobject obj, VideoRendererContext *context) {
    // Get a reference to this object's class
    jclass cls = env->GetObjectClass(obj);

    if (nullptr == cls) {
        LOGE("Could not find com/media/camera/preview/render/VideoRenderer.");
        return;
    }

    // Get the Field ID of the "mNativeContext" variable
    jni_fields.context = env->GetFieldID(cls, "mNativeContext", "J");
    if (nullptr == jni_fields.context) {
        LOGE("Could not find mNativeContext.");
        return;
    }

    env->SetLongField(obj, jni_fields.context, (jlong) context);
}

void VideoRendererContext::deleteContext(JNIEnv *env, jobject obj) {
    if (nullptr == jni_fields.context) {
        LOGE("Could not find mNativeContext.");
        return;
    }

    auto *context = reinterpret_cast<VideoRendererContext *>(env->GetLongField(obj, jni_fields.context));

    delete context;

    env->SetLongField(obj, jni_fields.context, 0L);
}

VideoRendererContext *VideoRendererContext::getContext(JNIEnv *env, jobject obj) {
    if (nullptr == jni_fields.context) {
        LOGE("Could not find mNativeContext.");
        return nullptr;
    }

    auto *context = reinterpret_cast<VideoRendererContext *>(env->GetLongField(obj, jni_fields.context));

    return context;
}
