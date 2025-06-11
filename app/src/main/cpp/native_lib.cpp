#include <jni.h>
#include <string>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_splitterrr_ui_main_NativeBridge_runModel(
        JNIEnv* env,
        jclass ,
        jstring prompt) {

    const char* input = env->GetStringUTFChars(prompt, 0);

    std::string response = "Gemma says: [mock output for '" + std::string(input) + "']";

    env->ReleaseStringUTFChars(prompt, input);
    return env->NewStringUTF(response.c_str());
}
