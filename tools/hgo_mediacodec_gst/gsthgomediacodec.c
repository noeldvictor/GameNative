#include <gst/gst.h>
#include <gst/video/gstvideodecoder.h>
#include <gst/video/video.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <string.h>

GST_DEBUG_CATEGORY_STATIC (hgo_mediacodec_debug);
#define GST_CAT_DEFAULT hgo_mediacodec_debug

#define HGO_COLOR_FORMAT_YUV420_PLANAR 19
#define HGO_COLOR_FORMAT_YUV420_SEMIPLANAR 21
#define HGO_COLOR_FORMAT_YUV420_FLEXIBLE 0x7F420888

typedef struct _GstHgoMediaCodecH264Dec GstHgoMediaCodecH264Dec;
typedef struct _GstHgoMediaCodecH264DecClass GstHgoMediaCodecH264DecClass;

struct _GstHgoMediaCodecH264Dec
{
  GstVideoDecoder parent;

  AMediaCodec *codec;
  gboolean started;
  gchar *decoder_name;

  gint width;
  gint height;
  gint stride;
  gint slice_height;
  gint color_format;
  gint nal_length_size;
  gboolean avc_format;
  gboolean output_ready;

  GstVideoCodecState *input_state;
  GstVideoCodecState *output_state;
  GstVideoFormat gst_format;
  GQueue pending_frames;

  guint64 queued_frames;
  guint64 output_frames;
};

struct _GstHgoMediaCodecH264DecClass
{
  GstVideoDecoderClass parent_class;
};

#define GST_TYPE_HGO_MEDIACODEC_H264_DEC (gst_hgo_mediacodec_h264_dec_get_type())
#define GST_HGO_MEDIACODEC_H264_DEC(obj) \
  (G_TYPE_CHECK_INSTANCE_CAST ((obj), GST_TYPE_HGO_MEDIACODEC_H264_DEC, GstHgoMediaCodecH264Dec))

GType gst_hgo_mediacodec_h264_dec_get_type (void);

G_DEFINE_TYPE (GstHgoMediaCodecH264Dec, gst_hgo_mediacodec_h264_dec,
    GST_TYPE_VIDEO_DECODER)

static GstStaticPadTemplate sink_template = GST_STATIC_PAD_TEMPLATE ("sink",
    GST_PAD_SINK,
    GST_PAD_ALWAYS,
    GST_STATIC_CAPS ("video/x-h264, "
        "stream-format=(string){ avc, byte-stream }, "
        "alignment=(string){ au, nal }; video/x-h264"));

static GstStaticPadTemplate src_template = GST_STATIC_PAD_TEMPLATE ("src",
    GST_PAD_SRC,
    GST_PAD_ALWAYS,
    GST_STATIC_CAPS ("video/x-raw, format=(string){ I420, NV12 }"));

static guint32
hgo_read_be (const guint8 * data, gint n)
{
  guint32 value = 0;
  gint i;

  for (i = 0; i < n; i++)
    value = (value << 8) | data[i];

  return value;
}

static void
hgo_append_start_code_nal (GByteArray * array, const guint8 * data, gsize size)
{
  static const guint8 start_code[] = { 0x00, 0x00, 0x00, 0x01 };

  g_byte_array_append (array, start_code, sizeof (start_code));
  g_byte_array_append (array, data, (guint) size);
}

static gboolean
hgo_parse_avc_codec_data (GstHgoMediaCodecH264Dec * self, GstBuffer * codec_data,
    GByteArray ** csd0, GByteArray ** csd1)
{
  GstMapInfo map;
  const guint8 *data;
  gsize size;
  gsize off;
  guint n_sps;
  guint n_pps;
  guint i;

  *csd0 = NULL;
  *csd1 = NULL;

  if (!codec_data || !gst_buffer_map (codec_data, &map, GST_MAP_READ))
    return FALSE;

  data = map.data;
  size = map.size;
  if (size < 7 || data[0] != 1) {
    gst_buffer_unmap (codec_data, &map);
    return FALSE;
  }

  self->nal_length_size = (data[4] & 0x03) + 1;
  off = 6;
  n_sps = data[5] & 0x1f;
  *csd0 = g_byte_array_new ();

  for (i = 0; i < n_sps; i++) {
    guint16 len;
    if (off + 2 > size)
      goto fail;
    len = (data[off] << 8) | data[off + 1];
    off += 2;
    if (off + len > size)
      goto fail;
    hgo_append_start_code_nal (*csd0, data + off, len);
    off += len;
  }

  if (off + 1 > size)
    goto fail;
  n_pps = data[off++];
  *csd1 = g_byte_array_new ();

  for (i = 0; i < n_pps; i++) {
    guint16 len;
    if (off + 2 > size)
      goto fail;
    len = (data[off] << 8) | data[off + 1];
    off += 2;
    if (off + len > size)
      goto fail;
    hgo_append_start_code_nal (*csd1, data + off, len);
    off += len;
  }

  GST_INFO_OBJECT (self, "Parsed AVC codec_data: nal_length_size=%d csd0=%u csd1=%u",
      self->nal_length_size, (*csd0)->len, (*csd1)->len);
  gst_buffer_unmap (codec_data, &map);
  return TRUE;

fail:
  GST_WARNING_OBJECT (self, "Failed to parse AVC codec_data");
  if (*csd0)
    g_byte_array_unref (*csd0);
  if (*csd1)
    g_byte_array_unref (*csd1);
  *csd0 = NULL;
  *csd1 = NULL;
  gst_buffer_unmap (codec_data, &map);
  return FALSE;
}

static gboolean
hgo_avc_sample_to_annexb (GstHgoMediaCodecH264Dec * self, const guint8 * data,
    gsize size, GByteArray ** out)
{
  gsize off = 0;

  *out = NULL;
  if (!self->avc_format || self->nal_length_size <= 0)
    return FALSE;

  *out = g_byte_array_sized_new ((guint) size + 64);

  while (off + self->nal_length_size <= size) {
    guint32 nal_size = hgo_read_be (data + off, self->nal_length_size);
    off += self->nal_length_size;
    if (nal_size == 0)
      continue;
    if (off + nal_size > size)
      goto fail;
    hgo_append_start_code_nal (*out, data + off, nal_size);
    off += nal_size;
  }

  if (off != size)
    goto fail;

  return TRUE;

fail:
  GST_WARNING_OBJECT (self, "Failed to convert AVC sample to Annex B");
  g_byte_array_unref (*out);
  *out = NULL;
  return FALSE;
}

static void
hgo_copy_plane (guint8 * dst, gint dst_stride, const guint8 * src,
    gint src_stride, gint width, gint height)
{
  gint y;

  for (y = 0; y < height; y++)
    memcpy (dst + (y * dst_stride), src + (y * src_stride), width);
}

static GstVideoFormat
hgo_gst_format_from_color (gint color_format)
{
  if (color_format == HGO_COLOR_FORMAT_YUV420_SEMIPLANAR)
    return GST_VIDEO_FORMAT_NV12;

  return GST_VIDEO_FORMAT_I420;
}

static gboolean
hgo_update_output_format (GstHgoMediaCodecH264Dec * self,
    GstVideoDecoder * decoder)
{
  AMediaFormat *format;
  const char *format_string;
  gint32 value;
  GstVideoCodecState *state;

  format = AMediaCodec_getOutputFormat (self->codec);
  if (!format)
    return FALSE;

  format_string = AMediaFormat_toString (format);
  if (format_string)
    GST_INFO_OBJECT (self, "HGO MediaCodec output format: %s", format_string);

  if (AMediaFormat_getInt32 (format, AMEDIAFORMAT_KEY_WIDTH, &value))
    self->width = value;
  if (AMediaFormat_getInt32 (format, AMEDIAFORMAT_KEY_HEIGHT, &value))
    self->height = value;
  if (AMediaFormat_getInt32 (format, AMEDIAFORMAT_KEY_STRIDE, &value))
    self->stride = value;
  if (AMediaFormat_getInt32 (format, AMEDIAFORMAT_KEY_SLICE_HEIGHT, &value))
    self->slice_height = value;
  if (AMediaFormat_getInt32 (format, AMEDIAFORMAT_KEY_COLOR_FORMAT, &value))
    self->color_format = value;

  if (self->stride <= 0)
    self->stride = self->width;
  if (self->slice_height <= 0)
    self->slice_height = self->height;

  self->gst_format = hgo_gst_format_from_color (self->color_format);
  state = gst_video_decoder_set_output_state (decoder, self->gst_format,
      self->width, self->height, self->input_state);
  if (self->output_state)
    gst_video_codec_state_unref (self->output_state);
  self->output_state = state;
  self->output_ready = TRUE;

  GST_INFO_OBJECT (self,
      "HGO MediaCodec raw output negotiated: %s %dx%d stride=%d slice=%d color=0x%x",
      gst_video_format_to_string (self->gst_format), self->width, self->height,
      self->stride, self->slice_height, self->color_format);

  AMediaFormat_delete (format);
  return TRUE;
}

static gboolean
hgo_copy_output_buffer (GstHgoMediaCodecH264Dec * self,
    GstVideoCodecFrame * frame, const guint8 * src, gsize size)
{
  GstVideoFrame vframe;
  GstVideoInfo *info;
  const guint8 *src_y;
  const guint8 *src_uv;
  const guint8 *src_u;
  const guint8 *src_v;
  gint chroma_width;
  gint chroma_height;

  if (!self->output_state)
    return FALSE;

  info = &self->output_state->info;
  if (!gst_video_frame_map (&vframe, info, frame->output_buffer, GST_MAP_WRITE))
    return FALSE;

  src_y = src;
  chroma_width = self->width / 2;
  chroma_height = self->height / 2;

  if (self->gst_format == GST_VIDEO_FORMAT_NV12) {
    src_uv = src_y + ((gsize) self->stride * self->slice_height);
    if ((gsize) (src_uv - src_y) + ((gsize) self->stride * chroma_height) > size)
      goto too_small;
    hgo_copy_plane (GST_VIDEO_FRAME_PLANE_DATA (&vframe, 0),
        GST_VIDEO_FRAME_PLANE_STRIDE (&vframe, 0), src_y, self->stride,
        self->width, self->height);
    hgo_copy_plane (GST_VIDEO_FRAME_PLANE_DATA (&vframe, 1),
        GST_VIDEO_FRAME_PLANE_STRIDE (&vframe, 1), src_uv, self->stride,
        self->width, chroma_height);
  } else {
    src_u = src_y + ((gsize) self->stride * self->slice_height);
    src_v = src_u + ((gsize) (self->stride / 2) * (self->slice_height / 2));
    if ((gsize) (src_v - src_y) + ((gsize) (self->stride / 2) * chroma_height) > size)
      goto too_small;
    hgo_copy_plane (GST_VIDEO_FRAME_PLANE_DATA (&vframe, 0),
        GST_VIDEO_FRAME_PLANE_STRIDE (&vframe, 0), src_y, self->stride,
        self->width, self->height);
    hgo_copy_plane (GST_VIDEO_FRAME_PLANE_DATA (&vframe, 1),
        GST_VIDEO_FRAME_PLANE_STRIDE (&vframe, 1), src_u, self->stride / 2,
        chroma_width, chroma_height);
    hgo_copy_plane (GST_VIDEO_FRAME_PLANE_DATA (&vframe, 2),
        GST_VIDEO_FRAME_PLANE_STRIDE (&vframe, 2), src_v, self->stride / 2,
        chroma_width, chroma_height);
  }

  gst_video_frame_unmap (&vframe);
  return TRUE;

too_small:
  GST_WARNING_OBJECT (self, "MediaCodec output buffer too small for negotiated raw frame");
  gst_video_frame_unmap (&vframe);
  return FALSE;
}

static void
hgo_clear_pending_frames (GstHgoMediaCodecH264Dec * self,
    GstVideoDecoder * decoder, gboolean drop)
{
  while (!g_queue_is_empty (&self->pending_frames)) {
    GstVideoCodecFrame *frame = g_queue_pop_head (&self->pending_frames);
    if (drop && decoder)
      gst_video_decoder_drop_frame (decoder, frame);
    else
      gst_video_codec_frame_unref (frame);
  }
}

static void
hgo_stop_codec (GstHgoMediaCodecH264Dec * self, GstVideoDecoder * decoder)
{
  if (self->codec) {
    if (self->started) {
      AMediaCodec_stop (self->codec);
      self->started = FALSE;
    }
    AMediaCodec_delete (self->codec);
    self->codec = NULL;
  }

  hgo_clear_pending_frames (self, decoder, TRUE);

  if (self->input_state) {
    gst_video_codec_state_unref (self->input_state);
    self->input_state = NULL;
  }
  if (self->output_state) {
    gst_video_codec_state_unref (self->output_state);
    self->output_state = NULL;
  }

  g_clear_pointer (&self->decoder_name, g_free);
  self->output_ready = FALSE;
}

static GstFlowReturn
hgo_drain_output (GstHgoMediaCodecH264Dec * self, GstVideoDecoder * decoder,
    gint64 timeout_us, gboolean require_frame)
{
  GstFlowReturn flow = GST_FLOW_OK;
  gint attempts = require_frame ? 40 : 8;

  while (attempts-- > 0) {
    AMediaCodecBufferInfo buffer_info;
    ssize_t idx;

    memset (&buffer_info, 0, sizeof (buffer_info));
    idx = AMediaCodec_dequeueOutputBuffer (self->codec, &buffer_info, timeout_us);

    if (idx == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
      break;

    if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
      hgo_update_output_format (self, decoder);
      continue;
    }

    if (idx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED)
      continue;

    if (idx < 0) {
      GST_WARNING_OBJECT (self, "Unexpected MediaCodec output result: %zd", idx);
      break;
    }

    if ((buffer_info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) ||
        buffer_info.size <= 0) {
      AMediaCodec_releaseOutputBuffer (self->codec, (size_t) idx, FALSE);
      continue;
    }

    if (!self->output_ready && !hgo_update_output_format (self, decoder)) {
      AMediaCodec_releaseOutputBuffer (self->codec, (size_t) idx, FALSE);
      continue;
    }

    if (g_queue_is_empty (&self->pending_frames)) {
      GST_WARNING_OBJECT (self, "Decoded output has no pending GstVideoCodecFrame");
      AMediaCodec_releaseOutputBuffer (self->codec, (size_t) idx, FALSE);
      continue;
    }

    {
      GstVideoCodecFrame *frame = g_queue_pop_head (&self->pending_frames);
      size_t out_size = 0;
      guint8 *out_data = AMediaCodec_getOutputBuffer (self->codec, (size_t) idx,
          &out_size);

      flow = gst_video_decoder_allocate_output_frame (decoder, frame);
      if (flow == GST_FLOW_OK && out_data &&
          (gsize) buffer_info.offset + buffer_info.size <= out_size) {
        if (!hgo_copy_output_buffer (self, frame, out_data + buffer_info.offset,
                buffer_info.size))
          flow = GST_FLOW_ERROR;
      } else if (flow == GST_FLOW_OK) {
        GST_WARNING_OBJECT (self, "Could not map MediaCodec output buffer");
        flow = GST_FLOW_ERROR;
      }

      AMediaCodec_releaseOutputBuffer (self->codec, (size_t) idx, FALSE);

      if (flow == GST_FLOW_OK) {
        self->output_frames++;
        GST_LOG_OBJECT (self, "Finished decoded frame #%" G_GUINT64_FORMAT,
            self->output_frames);
        flow = gst_video_decoder_finish_frame (decoder, frame);
      } else {
        gst_video_decoder_drop_frame (decoder, frame);
      }
    }

    if (require_frame || flow != GST_FLOW_OK)
      break;
  }

  return flow;
}

static gboolean
gst_hgo_mediacodec_h264_dec_start (GstVideoDecoder * decoder)
{
  GstHgoMediaCodecH264Dec *self = GST_HGO_MEDIACODEC_H264_DEC (decoder);

  gst_video_decoder_set_packetized (decoder, TRUE);
  gst_video_decoder_set_needs_format (decoder, TRUE);
  g_queue_init (&self->pending_frames);
  self->width = 1280;
  self->height = 720;
  self->stride = 1280;
  self->slice_height = 720;
  self->color_format = HGO_COLOR_FORMAT_YUV420_FLEXIBLE;
  self->gst_format = GST_VIDEO_FORMAT_I420;
  self->nal_length_size = 4;
  self->queued_frames = 0;
  self->output_frames = 0;
  return TRUE;
}

static gboolean
gst_hgo_mediacodec_h264_dec_stop (GstVideoDecoder * decoder)
{
  GstHgoMediaCodecH264Dec *self = GST_HGO_MEDIACODEC_H264_DEC (decoder);

  hgo_stop_codec (self, decoder);
  return TRUE;
}

static gboolean
gst_hgo_mediacodec_h264_dec_flush (GstVideoDecoder * decoder)
{
  GstHgoMediaCodecH264Dec *self = GST_HGO_MEDIACODEC_H264_DEC (decoder);

  hgo_clear_pending_frames (self, decoder, TRUE);
  if (self->codec && self->started)
    AMediaCodec_flush (self->codec);
  return TRUE;
}

static gboolean
gst_hgo_mediacodec_h264_dec_set_format (GstVideoDecoder * decoder,
    GstVideoCodecState * state)
{
  GstHgoMediaCodecH264Dec *self = GST_HGO_MEDIACODEC_H264_DEC (decoder);
  GstStructure *structure;
  const gchar *stream_format;
  const GValue *codec_data_value;
  GstBuffer *codec_data = NULL;
  GByteArray *csd0 = NULL;
  GByteArray *csd1 = NULL;
  AMediaFormat *format;
  media_status_t status;
  char *codec_name = NULL;

  hgo_stop_codec (self, decoder);

  if (self->input_state)
    gst_video_codec_state_unref (self->input_state);
  self->input_state = gst_video_codec_state_ref (state);

  self->width = GST_VIDEO_INFO_WIDTH (&state->info);
  self->height = GST_VIDEO_INFO_HEIGHT (&state->info);
  if (self->width <= 0)
    self->width = 1280;
  if (self->height <= 0)
    self->height = 720;
  self->stride = self->width;
  self->slice_height = self->height;

  structure = gst_caps_get_structure (state->caps, 0);
  stream_format = gst_structure_get_string (structure, "stream-format");
  self->avc_format = !stream_format || g_strcmp0 (stream_format, "avc") == 0;
  codec_data_value = gst_structure_get_value (structure, "codec_data");
  if (codec_data_value && GST_VALUE_HOLDS_BUFFER (codec_data_value))
    codec_data = gst_value_get_buffer (codec_data_value);

  if (self->avc_format && codec_data)
    hgo_parse_avc_codec_data (self, codec_data, &csd0, &csd1);

  self->codec = AMediaCodec_createDecoderByType ("video/avc");
  if (!self->codec) {
    GST_ERROR_OBJECT (self, "AMediaCodec_createDecoderByType(video/avc) failed");
    goto fail;
  }

  status = AMediaCodec_getName (self->codec, &codec_name);
  if (status == AMEDIA_OK && codec_name) {
    self->decoder_name = g_strdup (codec_name);
    GST_INFO_OBJECT (self, "HGO MediaCodec decoder selected: %s",
        self->decoder_name);
    AMediaCodec_releaseName (self->codec, codec_name);
  } else {
    GST_INFO_OBJECT (self, "HGO MediaCodec decoder created; name unavailable status=%d",
        status);
  }

  format = AMediaFormat_new ();
  AMediaFormat_setString (format, AMEDIAFORMAT_KEY_MIME, "video/avc");
  AMediaFormat_setInt32 (format, AMEDIAFORMAT_KEY_WIDTH, self->width);
  AMediaFormat_setInt32 (format, AMEDIAFORMAT_KEY_HEIGHT, self->height);
  if (csd0 && csd0->len)
    AMediaFormat_setBuffer (format, "csd-0", csd0->data, csd0->len);
  if (csd1 && csd1->len)
    AMediaFormat_setBuffer (format, "csd-1", csd1->data, csd1->len);

  status = AMediaCodec_configure (self->codec, format, NULL, NULL, 0);
  AMediaFormat_delete (format);
  if (csd0)
    g_byte_array_unref (csd0);
  if (csd1)
    g_byte_array_unref (csd1);

  if (status != AMEDIA_OK) {
    GST_ERROR_OBJECT (self, "AMediaCodec_configure failed: %d", status);
    goto fail;
  }

  status = AMediaCodec_start (self->codec);
  if (status != AMEDIA_OK) {
    GST_ERROR_OBJECT (self, "AMediaCodec_start failed: %d", status);
    goto fail;
  }

  self->started = TRUE;
  self->gst_format = GST_VIDEO_FORMAT_I420;
  self->output_state = gst_video_decoder_set_output_state (decoder,
      self->gst_format, self->width, self->height, state);
  self->output_ready = TRUE;

  GST_INFO_OBJECT (self, "HGO MediaCodec H.264 decoder started for %dx%d stream-format=%s",
      self->width, self->height, stream_format ? stream_format : "(default-avc)");
  return TRUE;

fail:
  if (csd0)
    g_byte_array_unref (csd0);
  if (csd1)
    g_byte_array_unref (csd1);
  hgo_stop_codec (self, decoder);
  return FALSE;
}

static GstFlowReturn
gst_hgo_mediacodec_h264_dec_handle_frame (GstVideoDecoder * decoder,
    GstVideoCodecFrame * frame)
{
  GstHgoMediaCodecH264Dec *self = GST_HGO_MEDIACODEC_H264_DEC (decoder);
  GstMapInfo map;
  GByteArray *annexb = NULL;
  const guint8 *input_data;
  gsize input_size;
  ssize_t idx;
  guint8 *codec_input;
  size_t codec_input_size = 0;
  media_status_t status;
  gint retry;
  guint64 pts_us = 0;

  if (!self->codec || !self->started) {
    GST_ERROR_OBJECT (self, "Frame arrived before MediaCodec was started");
    return gst_video_decoder_drop_frame (decoder, frame);
  }

  if (!gst_buffer_map (frame->input_buffer, &map, GST_MAP_READ))
    return gst_video_decoder_drop_frame (decoder, frame);

  input_data = map.data;
  input_size = map.size;
  if (hgo_avc_sample_to_annexb (self, map.data, map.size, &annexb)) {
    input_data = annexb->data;
    input_size = annexb->len;
  }

  idx = AMediaCodec_dequeueInputBuffer (self->codec, 10000);
  for (retry = 0; idx == AMEDIACODEC_INFO_TRY_AGAIN_LATER && retry < 8; retry++) {
    hgo_drain_output (self, decoder, 0, FALSE);
    idx = AMediaCodec_dequeueInputBuffer (self->codec, 10000);
  }

  if (idx < 0) {
    GST_WARNING_OBJECT (self, "No MediaCodec input buffer available: %zd", idx);
    gst_buffer_unmap (frame->input_buffer, &map);
    if (annexb)
      g_byte_array_unref (annexb);
    return gst_video_decoder_drop_frame (decoder, frame);
  }

  codec_input = AMediaCodec_getInputBuffer (self->codec, (size_t) idx,
      &codec_input_size);
  if (!codec_input || codec_input_size < input_size) {
    GST_WARNING_OBJECT (self, "MediaCodec input buffer too small: have=%u need=%u",
        (guint) codec_input_size, (guint) input_size);
    gst_buffer_unmap (frame->input_buffer, &map);
    if (annexb)
      g_byte_array_unref (annexb);
    return gst_video_decoder_drop_frame (decoder, frame);
  }

  memcpy (codec_input, input_data, input_size);
  if (GST_CLOCK_TIME_IS_VALID (frame->pts))
    pts_us = frame->pts / GST_USECOND;

  status = AMediaCodec_queueInputBuffer (self->codec, (size_t) idx, 0,
      input_size, pts_us, 0);
  gst_buffer_unmap (frame->input_buffer, &map);
  if (annexb)
    g_byte_array_unref (annexb);

  if (status != AMEDIA_OK) {
    GST_WARNING_OBJECT (self, "AMediaCodec_queueInputBuffer failed: %d", status);
    return gst_video_decoder_drop_frame (decoder, frame);
  }

  self->queued_frames++;
  g_queue_push_tail (&self->pending_frames, frame);
  return hgo_drain_output (self, decoder, 10000, TRUE);
}

static GstFlowReturn
gst_hgo_mediacodec_h264_dec_finish (GstVideoDecoder * decoder)
{
  GstHgoMediaCodecH264Dec *self = GST_HGO_MEDIACODEC_H264_DEC (decoder);
  GstFlowReturn flow = GST_FLOW_OK;
  gint i;

  if (!self->codec || !self->started)
    return GST_FLOW_OK;

  for (i = 0; i < 80 && !g_queue_is_empty (&self->pending_frames); i++) {
    flow = hgo_drain_output (self, decoder, 10000, TRUE);
    if (flow != GST_FLOW_OK)
      break;
  }

  GST_INFO_OBJECT (self, "HGO MediaCodec finish: queued=%" G_GUINT64_FORMAT
      " output=%" G_GUINT64_FORMAT " pending=%u", self->queued_frames,
      self->output_frames, g_queue_get_length (&self->pending_frames));
  return flow;
}

static void
gst_hgo_mediacodec_h264_dec_finalize (GObject * object)
{
  GstHgoMediaCodecH264Dec *self = GST_HGO_MEDIACODEC_H264_DEC (object);

  hgo_stop_codec (self, GST_VIDEO_DECODER (self));
  G_OBJECT_CLASS (gst_hgo_mediacodec_h264_dec_parent_class)->finalize (object);
}

static void
gst_hgo_mediacodec_h264_dec_class_init (GstHgoMediaCodecH264DecClass * klass)
{
  GObjectClass *object_class = G_OBJECT_CLASS (klass);
  GstElementClass *element_class = GST_ELEMENT_CLASS (klass);
  GstVideoDecoderClass *decoder_class = GST_VIDEO_DECODER_CLASS (klass);

  object_class->finalize = gst_hgo_mediacodec_h264_dec_finalize;

  gst_element_class_add_static_pad_template (element_class, &sink_template);
  gst_element_class_add_static_pad_template (element_class, &src_template);
  gst_element_class_set_static_metadata (element_class,
      "HGO Android MediaCodec H.264 Decoder",
      "Codec/Decoder/Video/Hardware",
      "Uses Android NDK AMediaCodec for H.264 decode in GameNative",
      "HGO");

  decoder_class->start = GST_DEBUG_FUNCPTR (gst_hgo_mediacodec_h264_dec_start);
  decoder_class->stop = GST_DEBUG_FUNCPTR (gst_hgo_mediacodec_h264_dec_stop);
  decoder_class->flush = GST_DEBUG_FUNCPTR (gst_hgo_mediacodec_h264_dec_flush);
  decoder_class->set_format =
      GST_DEBUG_FUNCPTR (gst_hgo_mediacodec_h264_dec_set_format);
  decoder_class->handle_frame =
      GST_DEBUG_FUNCPTR (gst_hgo_mediacodec_h264_dec_handle_frame);
  decoder_class->finish = GST_DEBUG_FUNCPTR (gst_hgo_mediacodec_h264_dec_finish);
}

static void
gst_hgo_mediacodec_h264_dec_init (GstHgoMediaCodecH264Dec * self)
{
  self->color_format = HGO_COLOR_FORMAT_YUV420_FLEXIBLE;
  self->gst_format = GST_VIDEO_FORMAT_I420;
}

static gboolean
hgo_mediacodec_plugin_init (GstPlugin * plugin)
{
  guint rank = GST_RANK_NONE;
  const gchar *enable = g_getenv ("HGO_GST_MEDIACODEC_ENABLE");

  GST_DEBUG_CATEGORY_INIT (hgo_mediacodec_debug, "hgomediacodec", 0,
      "HGO native Android MediaCodec plugin");

  if (enable && g_strcmp0 (enable, "0") != 0)
    rank = GST_RANK_PRIMARY + 512;

  GST_INFO ("Registering hgomediacodech264dec rank=%u enable=%s", rank,
      enable ? enable : "(unset)");
  return gst_element_register (plugin, "hgomediacodech264dec", rank,
      GST_TYPE_HGO_MEDIACODEC_H264_DEC);
}

GST_PLUGIN_DEFINE (GST_VERSION_MAJOR,
    GST_VERSION_MINOR,
    hgomediacodec,
    "HGO native Android MediaCodec helpers",
    hgo_mediacodec_plugin_init,
    PACKAGE_VERSION,
    "LGPL",
    "HGO GameNative",
    "https://github.com/noeldvictor/GameNative")
