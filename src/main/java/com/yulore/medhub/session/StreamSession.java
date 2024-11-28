package com.yulore.medhub.session;

import com.yulore.util.ByteArrayListInputStream;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
@ToString(of={"_sessionId", "_contentId", "_playIdx"})
public class StreamSession {
    @AllArgsConstructor
    static public class EventContext {
        public String name;
        public Object payload;
        public long start;
        public StreamSession session;
    }

    @AllArgsConstructor
    static public class DataContext {
        public ByteBuffer data;
        public long start;
        public StreamSession session;
    }

    public StreamSession(final Consumer<EventContext> doSendEvent, final Consumer<DataContext> doSendData,
                         final String path, final String sessionId, final String contentId, final String playIdx) {
        _doSendEvent = doSendEvent;
        _doSendData = doSendData;
        _path = path;
        _sessionId = sessionId;
        _contentId = contentId;
        _playIdx = playIdx;
    }

    public void sendEvent(final long startInMs, final String eventName, final Object payload) {
        _doSendEvent.accept(new EventContext(eventName, payload, startInMs, this));
    }

    public void sendData(final long startInMs, final ByteBuffer data) {
        _doSendData.accept(new DataContext(data, startInMs, this));
    }

    public void lock() {
        _lock.lock();
        // log.info("lock session: {}", _lock);
    }

    public void unlock() {
        // log.info("unlock session: {}", _lock);
        _lock.unlock();
    }

    public boolean streaming() {
        try {
            _lock.lock();
            return _streaming;
        } finally {
            _lock.unlock();
        }
    }

    public InputStream genInputStream() {
        final InputStream is = new ByteArrayListInputStream(_bufs);
        try {
            is.skip(_pos);
        } catch (IOException ignored) {
        }
        return is;
    }

    public boolean needMoreData(final int count4read) {
        try {
            _lock.lock();
            return _streaming && _pos + count4read > _length;
        } finally {
            _lock.unlock();
        }
    }

    public int length() {
        try {
            _lock.lock();
            return _streaming ? Integer.MAX_VALUE : _length;
        } finally {
            _lock.unlock();
        }
    }

    public int tell() {
        try {
            _lock.lock();
            return _pos;
        } finally {
            _lock.unlock();
        }
    }

    public int seekFromStart(int pos) {
        try {
            _lock.lock();
            _pos = pos;
            return _pos;
        } finally {
            _lock.unlock();
        }
    }

    public void onDataChange(final Function<StreamSession, Boolean> onDataChanged) {
        _onDataChanged = onDataChanged;
    }

    public void appendData(final byte[] bytes) {
        try {
            _lock.lock();
            _bufs.add(bytes);
            _length += bytes.length;
            callOnDataChanged();
        } finally {
            _lock.unlock();
        }
    }

    public void appendCompleted() {
        try {
            _lock.lock();
            _streaming = false;
            callOnDataChanged();
        } finally {
            _lock.unlock();
        }
    }

    private void callOnDataChanged() {
        if (_onDataChanged != null) {
            if (_onDataChanged.apply(this)) {
                _onDataChanged = null;
            }
        }
    }

    final private String _path;
    final private String _sessionId;
    final private String _contentId;
    final private String _playIdx;
    final private Consumer<EventContext> _doSendEvent;
    final private Consumer<DataContext> _doSendData;

    private int _length = 0;
    private int _pos = 0;
    private boolean _streaming = true;

    final List<byte[]> _bufs = new ArrayList<>();
    private final Lock _lock = new ReentrantLock();
    private Function<StreamSession, Boolean> _onDataChanged = null;
}
