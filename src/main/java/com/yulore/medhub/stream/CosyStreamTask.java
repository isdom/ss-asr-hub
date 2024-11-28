package com.yulore.medhub.stream;

import com.alibaba.nls.client.protocol.OutputFormatEnum;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.tts.SpeechSynthesizer;
import com.alibaba.nls.client.protocol.tts.StreamInputTts;
import com.alibaba.nls.client.protocol.tts.StreamInputTtsListener;
import com.alibaba.nls.client.protocol.tts.StreamInputTtsResponse;
import com.google.common.base.Charsets;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.mgnt.utils.StringUnicodeEncoderDecoder;
import com.yulore.medhub.nls.CosyAgent;
import com.yulore.medhub.nls.TTSAgent;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
public class CosyStreamTask implements BuildStreamTask {
    final HashFunction _MD5 = Hashing.md5();

    public CosyStreamTask(final String path, final Supplier<CosyAgent> getCosyAgent,final Consumer<StreamInputTts> onSynthesizer) {
        _getCosyAgent = getCosyAgent;
        _onSynthesizer = onSynthesizer;
        // eg: {type=cosy,voice=xxx,url=ws://172.18.86.131:6789/cosy,vars_playback_id=<uuid>,content_id=2088788,vars_start_timestamp=1732028219711854}
        //          'StringUnicodeEncoderDecoder.encodeStringToUnicodeSequence(content)'.wav
        final int leftBracePos = path.indexOf('{');
        if (leftBracePos == -1) {
            log.warn("{} missing vars, ignore", path);
            throw new RuntimeException(path + " missing vars.");
        }
        final int rightBracePos = path.indexOf('}');
        if (rightBracePos == -1) {
            log.warn("{} missing vars, ignore", path);
            throw new RuntimeException(path + " missing vars.");
        }
        final String vars = path.substring(leftBracePos + 1, rightBracePos);

        _text = VarsUtil.extractValue(vars, "text");
        if (_text == null) {
            log.warn("{} missing text, ignore", path);
            throw new RuntimeException(path + " missing text.");
        }
        _text = StringUnicodeEncoderDecoder.decodeUnicodeSequenceToString(_text);
        _voice = VarsUtil.extractValue(vars, "voice");
        _pitchRate = VarsUtil.extractValue(vars, "pitch_rate");
        _speechRate = VarsUtil.extractValue(vars, "speech_rate");
        _cache = VarsUtil.extractValueAsBoolean(vars, "cache", false);
        _key = _cache ? buildKey() : null;
    }

    private String buildKey() {
        final StringBuilder sb = new StringBuilder();
        sb.append(_voice);
        sb.append(":");
        sb.append(_pitchRate);
        sb.append(":");
        sb.append(_speechRate);
        sb.append(":");
        sb.append(_text);

        return "tts-" + _MD5.hashString(sb.toString(), Charsets.UTF_8);
    }

    @Override
    public String key() {
        return _key;
    }

    @Override
    public void buildStream(final Consumer<byte[]> onPart, final Consumer<Boolean> onCompleted) {
        final CosyAgent agent = _getCosyAgent.get();
        log.info("start gen cosyvoice: {}", _text);
        final AtomicInteger idx = new AtomicInteger(0);
        final long startInMs = System.currentTimeMillis();
        final StreamInputTtsListener listener =  new StreamInputTtsListener() {
            //流入语音合成开始
            @Override
            public void onSynthesisStart(final StreamInputTtsResponse response) {
                log.info("onSynthesisStart: name: {} , status: {}", response.getName(), response.getStatus());
            }

            //服务端检测到了一句话的开始
            @Override
            public void onSentenceBegin(final StreamInputTtsResponse response) {
                log.info("onSentenceBegin: name: {} , status: {}", response.getName(), response.getStatus());
            }

            //服务端检测到了一句话的结束，获得这句话的起止位置和所有时间戳
            @Override
            public void onSentenceEnd(final StreamInputTtsResponse response) {
                log.info("onSentenceEnd: name: {} , status: {}， subtitles: {}",
                        response.getName(), response.getStatus(), response.getObject("subtitles"));
            }

            //流入语音合成结束
            @Override
            public void onSynthesisComplete(final StreamInputTtsResponse response) {
                // 调用onSynthesisComplete时，表示所有TTS数据已经接收完成，所有文本都已经合成音频并返回
                log.info("onSynthesisComplete: name: {} , status: {}", response.getName(), response.getStatus());
                onCompleted.accept(true);
                log.info("CosyStreamTask: gen wav stream cost={} ms", System.currentTimeMillis() - startInMs);
            }

            //收到语音合成的语音二进制数据
            @Override
            public void onAudioData(final ByteBuffer message) {
                byte[] bytesArray = new byte[message.remaining()];
                message.get(bytesArray, 0, bytesArray.length);
                log.info("CosyStreamTask: {}: onData {} bytes", idx.incrementAndGet(), bytesArray.length);
                onPart.accept(bytesArray);
            }

            //收到语音合成的增量音频时间戳
            @Override
            public void onSentenceSynthesis(final StreamInputTtsResponse response) {
                log.info("onSentenceSynthesis: name: {}, status: {}, subtitles: {}",
                        response.getName(), response.getStatus(), response.getObject("subtitles"));
            }

            @Override
            public void onFail(final StreamInputTtsResponse response) {
                // task_id是调用方和服务端通信的唯一标识，当遇到问题时，需要提供此task_id以便排查。
                log.info("session_id: {}, task_id: {}, status: {}, status_text: {}",
                        getStreamInputTts().getCurrentSessionId(), response.getTaskId(), response.getStatus(), response.getStatusText());
                onCompleted.accept(false);
            }
        };

        StreamInputTts synthesizer = null;
        try {
            synthesizer = agent.buildCosyvoiceSynthesizer(listener);
            if (null != _voice && !_voice.isEmpty()) {
                synthesizer.setVoice(_voice);
            }
            if (null != _pitchRate && !_pitchRate.isEmpty()) {
                synthesizer.setPitchRate(Integer.parseInt(_pitchRate));
            }
            if (null != _speechRate && !_speechRate.isEmpty()) {
                synthesizer.setSpeechRate(Integer.parseInt(_speechRate));
            }
            //音量，范围是0~100，可选，默认50。
            synthesizer.setVolume(100);

            //设置返回音频的编码格式
            synthesizer.setFormat(OutputFormatEnum.WAV);
            //设置返回音频的采样率。
            synthesizer.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);

            if (_onSynthesizer != null) {
                _onSynthesizer.accept(synthesizer);
            }

            synthesizer.startStreamInputTts();
            agent.incConnected();
            synthesizer.setMinSendIntervalMS(100);
            synthesizer.sendStreamInputTts(_text);
            //通知服务端流入文本数据发送完毕，阻塞等待服务端处理完成。
            synthesizer.stopStreamInputTts();
        } catch (Exception ex) {
            log.warn("buildStream failed: {}", ex.toString());
        } finally {
            //关闭连接
            if (null != synthesizer) {
                synthesizer.close();
            }
            agent.decConnected();
            agent.decConnection();
        }
    }

    private final Supplier<CosyAgent> _getCosyAgent;
    private final Consumer<StreamInputTts> _onSynthesizer;
    private String _key;
    private String _text;
    private String _voice;
    private String _pitchRate;
    private String _speechRate;
    private boolean _cache;
}
