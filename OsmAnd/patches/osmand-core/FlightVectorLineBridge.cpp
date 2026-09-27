#include <jni.h>
#include <memory>
#include <OsmAndCore/Map/VectorLine.h>
#include "../../src/Map/FlightMapProjection.h"

extern "C" JNIEXPORT void JNICALL
Java_net_osmand_core_jni_FlightVectorLineBridge_setFlightProjection(
    JNIEnv*, jclass, jboolean enabled)
{
    FlightMapProjection::enabled().store(enabled, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_net_osmand_core_jni_FlightVectorLineBridge_enableTubeNative(
    JNIEnv* env, jclass, jlong pointer, jobject)
{
    // SWIG uses a pointer to shared_ptr<VectorLine>, not a raw VectorLine pointer.
    const auto line = reinterpret_cast<std::shared_ptr<OsmAnd::VectorLine>*>(pointer);
    if (!line || !*line)
    {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Flight vector line was released");
        return;
    }
    (*line)->setIsTubular(true);
}
