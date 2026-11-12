package com.github.tvbox.osc.player;

import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;

import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 软解优先的解码器选择器。
 *
 * 兆懿 x86 模拟器的两条解码路径都不可靠：默认选择会挑硬件解码器，撞上 libmedia
 * 的 ResourceManagerService 空指针（SIGTRAP）；退到 IJK 又会被 libhoudini 转译
 * 拖垮（SIGSEGV 或严重丢帧）。而系统自带的 c2.android.* 是 x86 原生 Codec2 软解，
 * 不经转译，实测 720p 零丢帧。
 *
 * 因此这里只重排候选列表，不禁用硬件解码器：真机硬解可用时仍在列表内，
 * 行为不降级；模拟器上则优先落到 c2.android.*。
 */
public class C2SoftMediaCodecSelector implements MediaCodecSelector {

    private static final String CODEC2_SOFT_PREFIX = "c2.android.";
    private static final String OMX_SOFT_PREFIX = "OMX.google.";

    @Override
    public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder) throws MediaCodecUtil.DecoderQueryException {
        List<MediaCodecInfo> source = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
        if (source == null || source.size() <= 1) {
            return source;
        }
        List<MediaCodecInfo> codec2Soft = new ArrayList<>();
        List<MediaCodecInfo> hardware = new ArrayList<>();
        List<MediaCodecInfo> omxSoft = new ArrayList<>();
        for (MediaCodecInfo info : source) {
            String name = info.name == null ? "" : info.name;
            if (name.startsWith(CODEC2_SOFT_PREFIX)) {
                codec2Soft.add(info);
            } else if (name.startsWith(OMX_SOFT_PREFIX)) {
                omxSoft.add(info);
            } else {
                hardware.add(info);
            }
        }
        List<MediaCodecInfo> ordered = new ArrayList<>(source.size());
        ordered.addAll(codec2Soft);
        ordered.addAll(hardware);
        ordered.addAll(omxSoft);
        logChoice(mimeType, source, ordered);
        return ordered;
    }

    /**
     * 把候选顺序落盘，用于确认软解优先是否真的生效（模拟器 logcat 不可用）。
     */
    private void logChoice(String mimeType, List<MediaCodecInfo> source, List<MediaCodecInfo> ordered) {
        try {
            FileOutputStream fos = new FileOutputStream("/sdcard/tvbox_player.log", true);
            StringBuilder sb = new StringBuilder();
            sb.append("mime=").append(mimeType).append("\n");
            sb.append("  source=");
            for (MediaCodecInfo i : source) {
                sb.append(i.name).append(" ");
            }
            sb.append("\n  chosen=");
            for (MediaCodecInfo i : ordered) {
                sb.append(i.name).append(" ");
            }
            sb.append("\n");
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }
}
