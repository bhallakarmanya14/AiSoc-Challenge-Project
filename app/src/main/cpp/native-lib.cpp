#include <jni.h>
#include <string>
#include <android/log.h>
#include <vector>
#include <mutex>
#include "llama.h"

#define TAG "JNI"

struct llama_context * globalLanguageModelContext = nullptr;
struct llama_model * globalLanguageModel = nullptr;

static std::mutex globalLanguageModelMutex;
static std::string globalLanguageModelFilePath;
static bool isLlamaBackendInitialized = false;

static jmethodID cachedTokenCallbackMethodId = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_MainActivity_initializeNativeTranslationEngine(
        JNIEnv *jniEnvironment,
        jobject,
        jstring languageModelsDirectoryPathString,
        jstring languageModelFilenameString) {
    try {
        const char *languageModelsDirectoryPath = jniEnvironment->GetStringUTFChars(languageModelsDirectoryPathString, nullptr);
        const char *languageModelFilename = jniEnvironment->GetStringUTFChars(languageModelFilenameString, nullptr);

        globalLanguageModelFilePath = std::string(languageModelsDirectoryPath) + "/" + std::string(languageModelFilename);

        jniEnvironment->ReleaseStringUTFChars(languageModelsDirectoryPathString, languageModelsDirectoryPath);
        jniEnvironment->ReleaseStringUTFChars(languageModelFilenameString, languageModelFilename);

        __android_log_print(ANDROID_LOG_INFO, TAG, "LLaMA path: %s", globalLanguageModelFilePath.c_str());

        if (!isLlamaBackendInitialized) {
            llama_backend_init();
            isLlamaBackendInitialized = true;
        }

        __android_log_print(ANDROID_LOG_INFO, TAG, "Loading LLaMA model...");
        llama_model_params modelParameters = llama_model_default_params();
        globalLanguageModel = llama_model_load_from_file(globalLanguageModelFilePath.c_str(), modelParameters);
        if (!globalLanguageModel) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to load LLaMA model");
            jniEnvironment->ThrowNew(jniEnvironment->FindClass("java/lang/RuntimeException"),
                          ("Failed to load LLaMA model: " + globalLanguageModelFilePath).c_str());
            return;
        }

        llama_context_params contextParameters = llama_context_default_params();
        contextParameters.n_ctx   = 256;
        contextParameters.n_batch = 256;
        globalLanguageModelContext = llama_init_from_model(globalLanguageModel, contextParameters);
        if (!globalLanguageModelContext) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to create LLaMA context");
            jniEnvironment->ThrowNew(jniEnvironment->FindClass("java/lang/RuntimeException"),
                          "Failed to create LLaMA context");
            return;
        }

        __android_log_print(ANDROID_LOG_INFO, TAG, "LLaMA loaded OK - ready for translation");

    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Exception in initializeNativeTranslationEngine: %s", exception.what());
        jniEnvironment->ThrowNew(jniEnvironment->FindClass("java/lang/RuntimeException"), exception.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_myapplication_MainActivity_startNativeTranslationStream(
        JNIEnv* jniEnvironment,
        jobject activityInstance,
        jstring inputTextJavaString) {

    if (!globalLanguageModelContext) {
        jclass mainActivityClass = jniEnvironment->GetObjectClass(activityInstance);
        jmethodID receiveMethod = jniEnvironment->GetMethodID(mainActivityClass, "receiveTranslationWordToken", "(Ljava/lang/String;)V");
        jstring errorMessageString = jniEnvironment->NewStringUTF("[LLaMA not loaded]");
        jniEnvironment->CallVoidMethod(activityInstance, receiveMethod, errorMessageString);
        jniEnvironment->DeleteLocalRef(errorMessageString);
        return;
    }

    try {
        const char* inputText = jniEnvironment->GetStringUTFChars(inputTextJavaString, nullptr);
        std::string translationPrompt =
            "<|begin_of_text|>"
            "<|start_header_id|>system<|end_header_id|>\n\n"
            "You are a translator. Translate the user's English text into French. "
            "Output ONLY the French translation, nothing else.<|eot_id|>"
            "<|start_header_id|>user<|end_header_id|>\n\n"
            + std::string(inputText) +
            "<|eot_id|>"
            "<|start_header_id|>assistant<|end_header_id|>\n\n";
        jniEnvironment->ReleaseStringUTFChars(inputTextJavaString, inputText);

        std::lock_guard<std::mutex> lock(globalLanguageModelMutex);

        if (!cachedTokenCallbackMethodId) {
            jclass mainActivityClass = jniEnvironment->GetObjectClass(activityInstance);
            cachedTokenCallbackMethodId = jniEnvironment->GetMethodID(mainActivityClass, "receiveTranslationWordToken", "(Ljava/lang/String;)V");
        }

        const struct llama_vocab* vocabulary = llama_model_get_vocab(globalLanguageModel);

        std::vector<llama_token> tokensList(translationPrompt.length() + 16);
        int numberOfTokens = llama_tokenize(vocabulary, translationPrompt.c_str(), (int)translationPrompt.length(),
                                      tokensList.data(), (int)tokensList.size(), true, true);
        if (numberOfTokens < 0) {
            tokensList.resize(-numberOfTokens);
            numberOfTokens = llama_tokenize(vocabulary, translationPrompt.c_str(), (int)translationPrompt.length(),
                                      tokensList.data(), (int)tokensList.size(), true, true);
        }
        tokensList.resize(numberOfTokens);

        __android_log_print(ANDROID_LOG_INFO, TAG, "Translating %d tokens...", numberOfTokens);

        llama_memory_clear(llama_get_memory(globalLanguageModelContext), false);

        llama_batch decodingBatch = llama_batch_get_one(tokensList.data(), (int)tokensList.size());
        if (llama_decode(globalLanguageModelContext, decodingBatch)) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "llama_decode failed on prompt");
            return;
        }

        struct llama_sampler* tokenSampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(tokenSampler, llama_sampler_init_temp(0.0f));
        llama_sampler_chain_add(tokenSampler, llama_sampler_init_greedy());

        constexpr int maxOutputTokens = 128;

        for (int iterationIndex = 0; iterationIndex < maxOutputTokens; iterationIndex++) {
            llama_token generatedTokenId = llama_sampler_sample(tokenSampler, globalLanguageModelContext, -1);

            if (llama_vocab_is_eog(vocabulary, generatedTokenId)) break;

            char tokenStringBuffer[128];
            int numberOfCharacters = llama_token_to_piece(vocabulary, generatedTokenId, tokenStringBuffer, sizeof(tokenStringBuffer) - 1, 0, true);
            if (numberOfCharacters > 0) {
                tokenStringBuffer[numberOfCharacters] = '\0';
                jstring generatedTokenString = jniEnvironment->NewStringUTF(tokenStringBuffer);
                jniEnvironment->CallVoidMethod(activityInstance, cachedTokenCallbackMethodId, generatedTokenString);
                jniEnvironment->DeleteLocalRef(generatedTokenString);
            }

            decodingBatch = llama_batch_get_one(&generatedTokenId, 1);
            if (llama_decode(globalLanguageModelContext, decodingBatch)) break;
        }

        llama_sampler_free(tokenSampler);
        __android_log_print(ANDROID_LOG_INFO, TAG, "Translation complete");

    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Exception in startNativeTranslationStream: %s", exception.what());
    }
}
