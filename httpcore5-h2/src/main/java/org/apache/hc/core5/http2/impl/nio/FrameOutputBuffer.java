/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.core5.http2.impl.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.WritableByteChannel;

import org.apache.hc.core5.annotation.NotThreadSafe;
import org.apache.hc.core5.http2.H2ConnectionException;
import org.apache.hc.core5.http2.H2Error;
import org.apache.hc.core5.http2.frame.FrameConsts;
import org.apache.hc.core5.http2.frame.RawFrame;
import org.apache.hc.core5.http2.impl.BasicHttp2TransportMetrics;
import org.apache.hc.core5.http2.io.Http2TransportMetrics;
import org.apache.hc.core5.util.Args;

/**
 * Frame output buffer for HTTP/2 non-blocking connections.
 *
 * @since 5.0
 */
@NotThreadSafe
public final class FrameOutputBuffer {

    private final BasicHttp2TransportMetrics metrics;
    private final int maxFramePayloadSize;
    private final ByteBuffer buffer;

    public FrameOutputBuffer(final BasicHttp2TransportMetrics metrics, final int maxFramePayloadSize) {
        Args.notNull(metrics, "HTTP2 transport metrcis");
        Args.positive(maxFramePayloadSize, "Maximum payload size");
        this.metrics = metrics;
        this.maxFramePayloadSize = maxFramePayloadSize;
        this.buffer = ByteBuffer.allocate(FrameConsts.HEAD_LEN + maxFramePayloadSize);
    }

    public FrameOutputBuffer(final int maxFramePayloadSize) {
        this(new BasicHttp2TransportMetrics(), maxFramePayloadSize);
    }

    private void writeToChannel(final WritableByteChannel channel, final ByteBuffer src) throws IOException {
        final int bytesWritten = channel.write(src);
        if (bytesWritten > 0) {
            metrics.incrementBytesTransferred(bytesWritten);
        }
    }

    public void write(final RawFrame frame, final WritableByteChannel channel) throws IOException {
        Args.notNull(frame, "Frame");

        final ByteBuffer payload = frame.getPayload();
        if (payload != null && payload.remaining() > maxFramePayloadSize) {
            throw new H2ConnectionException(H2Error.FRAME_SIZE_ERROR, "Frame size exceeds maximum");
        }

        buffer.putInt((payload != null ? payload.remaining() << 8 : 0) | (frame.getType() & 0xff));
        buffer.put((byte) (frame.getFlags() & 0xff));
        buffer.putInt(frame.getStreamId());

        if (payload != null) {
            if (channel instanceof GatheringByteChannel) {
                buffer.flip();
                ((GatheringByteChannel) channel).write(new ByteBuffer[]{buffer, payload});
                buffer.compact();
                if (payload.hasRemaining()) {
                    buffer.put(payload);
                }
            } else {
                buffer.put(payload);
            }
        }

        if (buffer.position() > 0) {
            buffer.flip();
            writeToChannel(channel, buffer);
            buffer.compact();
        }

        metrics.incrementFramesTransferred();
    }

    public void flush(final WritableByteChannel channel) throws IOException {
        if (buffer.position() > 0) {
            buffer.flip();
            writeToChannel(channel, buffer);
            buffer.compact();
        }
    }

    public boolean isEmpty() {
        return buffer.position() == 0;
    }

    public Http2TransportMetrics getMetrics() {
        return metrics;
    }

}
