#include <arpa/inet.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <enet/enet.h>
#include <opus.h>
#include <rs.h>
#include <string.h>

typedef struct {
    ENetHost *host;
    ENetPeer *peer;
    uint32_t expected_ip;
    uint32_t token;
} Control;

static uint32_t ipv4(JNIEnv *env, jstring address) {
    const char *text = (*env)->GetStringUTFChars(env, address, NULL);
    struct in_addr parsed = {0};
    if (text != NULL) {
        inet_pton(AF_INET, text, &parsed);
        (*env)->ReleaseStringUTFChars(env, address, text);
    }
    return parsed.s_addr;
}

JNIEXPORT jlong JNICALL Java_dev_questheld_host_FixtureMedia_openControl(
        JNIEnv *env, jclass cls, jstring local, jstring peer, jint token) {
    (void) cls;
    Control *control = calloc(1, sizeof(*control));
    if (control == NULL) return 0;
    control->expected_ip = ipv4(env, peer);
    control->token = (uint32_t) token;
    struct sockaddr_in addr = {
        .sin_family = AF_INET,
        .sin_port = htons(47999),
        .sin_addr.s_addr = ipv4(env, local),
    };
    ENetAddress bind_addr = {0};
    enet_address_set_address(&bind_addr, (struct sockaddr *) &addr, sizeof(addr));
    if (addr.sin_addr.s_addr != 0 && control->expected_ip != 0 && enet_initialize() == 0) {
        control->host = enet_host_create(AF_INET, &bind_addr, 4, 0, 0, 0);
    }
    if (control->host != NULL) return (jlong) (intptr_t) control;
    free(control);
    return 0;
}

JNIEXPORT jbyteArray JNICALL Java_dev_questheld_host_FixtureMedia_pollControl(
        JNIEnv *env, jclass cls, jlong handle, jint timeout_ms) {
    (void) cls;
    Control *control = (Control *) (intptr_t) handle;
    ENetEvent event;
    int result = enet_host_service(control->host, &event, (enet_uint32) timeout_ms);
    if (result < 0) return (*env)->NewByteArray(env, 0);
    if (result == 0) return NULL;
    if (event.type == ENET_EVENT_TYPE_CONNECT) {
        const struct sockaddr_in *source = (const struct sockaddr_in *) &event.peer->address.address;
        if (source->sin_family != AF_INET || source->sin_addr.s_addr != control->expected_ip
                || event.data != control->token || control->peer != NULL) {
            enet_peer_disconnect_now(event.peer, 0);
        } else {
            control->peer = event.peer;
        }
    } else if (event.type == ENET_EVENT_TYPE_DISCONNECT && event.peer == control->peer) {
        control->peer = NULL;
        return (*env)->NewByteArray(env, 0);
    } else if (event.type == ENET_EVENT_TYPE_RECEIVE) {
        jbyteArray data = NULL;
        if (event.peer == control->peer && event.packet->dataLength <= 8192) {
            jsize size = (jsize) event.packet->dataLength;
            data = (*env)->NewByteArray(env, size);
            if (data != NULL) {
                (*env)->SetByteArrayRegion(env, data, 0, size, (const jbyte *) event.packet->data);
            }
        }
        enet_packet_destroy(event.packet);
        return data;
    }
    return NULL;
}

JNIEXPORT void JNICALL Java_dev_questheld_host_FixtureMedia_closeControl(
        JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    Control *control = (Control *) (intptr_t) handle;
    if (control->peer != NULL) enet_peer_disconnect_now(control->peer, 0);
    enet_host_destroy(control->host);
    enet_deinitialize();
    free(control);
}

JNIEXPORT jboolean JNICALL Java_dev_questheld_host_FixtureMedia_sendControl(
        JNIEnv *env, jclass cls, jlong handle, jbyteArray data) {
    (void) cls;
    Control *control = (Control *) (intptr_t) handle;
    if (control->peer == NULL) return JNI_FALSE;
    jsize size = (*env)->GetArrayLength(env, data);
    ENetPacket *packet = enet_packet_create(NULL, (size_t) size, ENET_PACKET_FLAG_RELIABLE);
    if (packet == NULL) return JNI_FALSE;
    (*env)->GetByteArrayRegion(env, data, 0, size, (jbyte *) packet->data);
    if (enet_peer_send(control->peer, 0, packet) != 0) {
        enet_packet_destroy(packet);
        return JNI_FALSE;
    }
    enet_host_flush(control->host);
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL Java_dev_questheld_host_LiveCapture_openOpus(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    int error;
    OpusEncoder *encoder = opus_encoder_create(48000, 2, OPUS_APPLICATION_RESTRICTED_LOWDELAY, &error);
    if (error != OPUS_OK) return 0;
    if (opus_encoder_ctl(encoder, OPUS_SET_BITRATE(96000)) != OPUS_OK
            || opus_encoder_ctl(encoder, OPUS_SET_VBR(0)) != OPUS_OK) {
        opus_encoder_destroy(encoder);
        return 0;
    }
    return (jlong) (intptr_t) encoder;
}

JNIEXPORT jbyteArray JNICALL Java_dev_questheld_host_LiveCapture_encodeOpus(
        JNIEnv *env, jclass cls, jlong handle, jshortArray pcm, jint samples) {
    (void) cls;
    if ((samples != 240 && samples != 480) || (*env)->GetArrayLength(env, pcm) != samples * 2) return NULL;
    opus_int16 input[960];
    unsigned char output[1275];
    (*env)->GetShortArrayRegion(env, pcm, 0, samples * 2, input);
    int size = opus_encode((OpusEncoder *) (intptr_t) handle, input, samples, output, sizeof(output));
    if (size <= 0) return NULL;
    jbyteArray packet = (*env)->NewByteArray(env, size);
    if (packet != NULL) (*env)->SetByteArrayRegion(env, packet, 0, size, (const jbyte *) output);
    return packet;
}

JNIEXPORT void JNICALL Java_dev_questheld_host_LiveCapture_closeOpus(JNIEnv *env, jclass cls, jlong handle) {
    (void) env;
    (void) cls;
    opus_encoder_destroy((OpusEncoder *) (intptr_t) handle);
}

JNIEXPORT jboolean JNICALL Java_dev_questheld_host_FixtureMedia_encodeParity(
        JNIEnv *env, jclass cls, jbyteArray data, jint size, jint originals, jint parity, jboolean audio) {
    (void) cls;
    if (size <= 0 || size > 1500 || originals <= 0 || parity <= 0 ||
            originals > DATA_SHARDS_MAX || parity > DATA_SHARDS_MAX ||
            originals + parity > DATA_SHARDS_MAX ||
            (audio && (originals != 4 || parity != 2)) ||
            (*env)->GetArrayLength(env, data) < size * (originals + parity)) return JNI_FALSE;
    reed_solomon *codec = reed_solomon_new(originals, parity);
    if (codec == NULL) return JNI_FALSE;
    if (audio) {
        // The stock GameStream audio matrix differs from the generic video matrix.
        const uint8_t coefficients[] = {0x77, 0x40, 0x38, 0x0e, 0xc7, 0xa7, 0x0d, 0x6c};
        memcpy(codec->p, coefficients, sizeof(coefficients));
    }
    uint8_t *buffer = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    int result = -1;
    if (buffer != NULL) {
        uint8_t *shards[DATA_SHARDS_MAX];
        for (int i = 0; i < originals + parity; i++) shards[i] = buffer + size * i;
        result = reed_solomon_encode(codec, shards, originals + parity, size);
        (*env)->ReleasePrimitiveArrayCritical(env, data, buffer, 0);
    }
    reed_solomon_release(codec);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}
