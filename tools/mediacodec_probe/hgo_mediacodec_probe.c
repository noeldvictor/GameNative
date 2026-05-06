#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *status_name(media_status_t status) {
    switch (status) {
    case AMEDIA_OK:
        return "AMEDIA_OK";
    case AMEDIA_ERROR_UNKNOWN:
        return "AMEDIA_ERROR_UNKNOWN";
    case AMEDIA_ERROR_MALFORMED:
        return "AMEDIA_ERROR_MALFORMED";
    case AMEDIA_ERROR_UNSUPPORTED:
        return "AMEDIA_ERROR_UNSUPPORTED";
    case AMEDIA_ERROR_INVALID_OBJECT:
        return "AMEDIA_ERROR_INVALID_OBJECT";
    case AMEDIA_ERROR_INVALID_PARAMETER:
        return "AMEDIA_ERROR_INVALID_PARAMETER";
    case AMEDIA_ERROR_INVALID_OPERATION:
        return "AMEDIA_ERROR_INVALID_OPERATION";
    default:
        return "AMEDIA_STATUS_OTHER";
    }
}

static int is_probably_hardware(const char *name) {
    if (!name) {
        return 0;
    }
    if (strstr(name, ".sw.") || strstr(name, "software") || strstr(name, "google")) {
        return 0;
    }
    return strstr(name, "qti") || strstr(name, "qcom") || strstr(name, "OMX.") ||
        strstr(name, "c2.") || strstr(name, "C2.");
}

int main(int argc, char **argv) {
    const char *mime = argc > 1 ? argv[1] : "video/avc";
    int width = argc > 2 ? atoi(argv[2]) : 1280;
    int height = argc > 3 ? atoi(argv[3]) : 720;
    int configure_start = argc > 4 && strcmp(argv[4], "--start") == 0;
    AMediaCodec *codec = NULL;
    AMediaFormat *format = NULL;
    char *component_name = NULL;
    media_status_t status;
    int exit_code = 0;

    printf("HGO MediaCodec probe\n");
    printf("Mime=%s\n", mime);
    printf("Size=%dx%d\n", width, height);
    printf("ConfigureStart=%d\n", configure_start);

    codec = AMediaCodec_createDecoderByType(mime);
    if (!codec) {
        fprintf(stderr, "DecoderCreate=FAILED\n");
        return 2;
    }

    printf("DecoderCreate=OK\n");

    status = AMediaCodec_getName(codec, &component_name);
    if (status == AMEDIA_OK && component_name) {
        printf("DecoderName=%s\n", component_name);
        printf("ProbablyHardware=%d\n", is_probably_hardware(component_name));
    } else {
        printf("DecoderNameStatus=%s(%d)\n", status_name(status), status);
        exit_code = 3;
    }

    if (configure_start) {
        format = AMediaFormat_new();
        if (!format) {
            fprintf(stderr, "FormatCreate=FAILED\n");
            exit_code = 4;
            goto done;
        }

        AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mime);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, width);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, height);

        status = AMediaCodec_configure(codec, format, NULL, NULL, 0);
        printf("ConfigureStatus=%s(%d)\n", status_name(status), status);
        if (status != AMEDIA_OK) {
            exit_code = 5;
            goto done;
        }

        status = AMediaCodec_start(codec);
        printf("StartStatus=%s(%d)\n", status_name(status), status);
        if (status != AMEDIA_OK) {
            exit_code = 6;
            goto done;
        }

        status = AMediaCodec_stop(codec);
        printf("StopStatus=%s(%d)\n", status_name(status), status);
    }

done:
    if (component_name) {
        AMediaCodec_releaseName(codec, component_name);
    }
    if (format) {
        AMediaFormat_delete(format);
    }
    if (codec) {
        AMediaCodec_delete(codec);
    }

    printf("ProbeExit=%d\n", exit_code);
    return exit_code;
}
