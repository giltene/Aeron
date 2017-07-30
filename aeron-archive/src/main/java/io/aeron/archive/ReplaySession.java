/*
 * Copyright 2014-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.archive;

import io.aeron.ExclusivePublication;
import io.aeron.Publication;
import io.aeron.archive.ArchiveConductor.ReplayPublicationSupplier;
import io.aeron.archive.codecs.ControlResponseCode;
import io.aeron.archive.codecs.RecordingDescriptorDecoder;
import io.aeron.logbuffer.ExclusiveBufferClaim;
import io.aeron.logbuffer.FrameDescriptor;
import io.aeron.protocol.DataHeaderFlyweight;
import org.agrona.CloseHelper;
import org.agrona.LangUtil;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

import static io.aeron.archive.RecordingFragmentReader.NULL_POSITION;
import static io.aeron.logbuffer.FrameDescriptor.frameFlags;
import static io.aeron.logbuffer.FrameDescriptor.frameType;
import static io.aeron.protocol.DataHeaderFlyweight.RESERVED_VALUE_OFFSET;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

/**
 * A replay session with a client which works through the required request response flow and streaming of recorded data.
 * The {@link ArchiveConductor} will initiate a session on receiving a ReplayRequest
 * (see {@link io.aeron.archive.codecs.ReplayRequestDecoder}). The session will:
 * <ul>
 * <li>Validate request parameters and respond with appropriate error if unable to replay </li>
 * <li>Wait for replay subscription to connect to the requested replay publication. If no subscription appears within
 * LINGER_LENGTH_MS the session will terminate and respond will error.</li>
 * <li>Once the replay publication is connected send an OK response to control client</li>
 * <li>Stream recorded data into the replayPublication {@link ExclusivePublication}</li>
 * <li>If the replay is aborted part way through, send a ReplayAborted message and terminate.</li>
 * <li>Once replay is terminated the publication kept open for LINGER_LENGTH_MS then the session is closed.</li>
 * </ul>
 */
class ReplaySession implements Session
{
    enum State
    {
        INIT, REPLAY, LINGER, INACTIVE, CLOSED
    }

    static final long LINGER_LENGTH_MS = 1000;
    private static final int REPLAY_BATCH_SIZE = Archive.Configuration.replayBatchSize();

    private final ExclusiveBufferClaim bufferClaim = new ExclusiveBufferClaim();
    private final RecordingFragmentReader.SimplifiedControlledPoll fragmentPoller = this::onFragment;

    private final long replaySessionId;
    private final long correlationId;
    private final Publication controlPublication;
    private final EpochClock epochClock;

    private final ExclusivePublication replayPublication;
    private final RecordingFragmentReader cursor;

    private ControlSessionProxy threadLocalControlSessionProxy;
    private State state = State.INIT;
    private long lingerSinceMs;

    ReplaySession(
        final long replayPosition,
        final long replayLength,
        final ReplayPublicationSupplier supplier,
        final Publication controlPublication,
        final File archiveDir,
        final ControlSessionProxy threadLocalControlSessionProxy,
        final long replaySessionId,
        final long correlationId,
        final EpochClock epochClock,
        final String replayChannel,
        final int replayStreamId,
        final UnsafeBuffer descriptorBuffer,
        final AtomicCounter recordingPosition)
    {
        this.controlPublication = controlPublication;
        this.threadLocalControlSessionProxy = threadLocalControlSessionProxy;
        this.replaySessionId = replaySessionId;
        this.correlationId = correlationId;
        this.epochClock = epochClock;
        this.lingerSinceMs = epochClock.time();

        final RecordingDescriptorDecoder descriptorDecoder = new RecordingDescriptorDecoder().wrap(
            descriptorBuffer,
            Catalog.DESCRIPTOR_HEADER_LENGTH,
            RecordingDescriptorDecoder.BLOCK_LENGTH,
            RecordingDescriptorDecoder.SCHEMA_VERSION);

        final long startPosition = descriptorDecoder.startPosition();
        final int mtuLength = descriptorDecoder.mtuLength();
        final int termBufferLength = descriptorDecoder.termBufferLength();
        final int initialTermId = descriptorDecoder.initialTermId();

        if (replayPosition - startPosition < 0)
        {
            final String errorMessage = "Requested replay start position(=" + replayPosition +
                ") is before recording start position(=" + startPosition + ")";
            closeOnError(new IllegalArgumentException(errorMessage), errorMessage);
            cursor = null;
            replayPublication = null;
            return;
        }

        final long stopPosition = descriptorDecoder.stopPosition();
        if (replayPosition - stopPosition >= 0)
        {
            final String errorMessage = "Requested replay start position(=" + replayPosition +
                ") is after recording stop position(=" + stopPosition + ")";
            closeOnError(new IllegalArgumentException(errorMessage), errorMessage);
            cursor = null;
            replayPublication = null;

            return;
        }

        RecordingFragmentReader cursor = null;
        try
        {
            cursor = new RecordingFragmentReader(
                descriptorDecoder,
                archiveDir,
                replayPosition,
                replayLength,
                recordingPosition);
        }
        catch (final IOException ex)
        {
            closeOnError(ex, "Failed to open cursor for a recording");
        }

        Objects.requireNonNull(cursor);
        this.cursor = cursor;

        ExclusivePublication replayPublication = null;
        try
        {
            replayPublication = supplier.newReplayPublication(
                replayChannel,
                replayStreamId,
                cursor.fromPosition(),
                mtuLength,
                initialTermId,
                termBufferLength);
        }
        catch (final Exception ex)
        {
            CloseHelper.quietClose(cursor);
            closeOnError(ex, "Failed to create replay publication");
        }

        this.replayPublication = replayPublication;
        threadLocalControlSessionProxy.sendOkResponse(correlationId, controlPublication);
    }

    public void close()
    {
        state = State.CLOSED;

        CloseHelper.quietClose(replayPublication);
        CloseHelper.quietClose(cursor);
    }

    public long sessionId()
    {
        return replaySessionId;
    }

    public int doWork()
    {
        int workDone = 0;

        if (state == State.REPLAY)
        {
            workDone += replay();
        }
        else if (state == State.INIT)
        {
            workDone += init();
        }
        else if (state == State.LINGER)
        {
            workDone += linger();
        }

        return workDone;
    }

    public void abort()
    {
        if (controlPublication.isConnected())
        {
            threadLocalControlSessionProxy.sendReplayAborted(
                correlationId,
                replaySessionId,
                replayPublication == null ? NULL_POSITION : replayPublication.position(),
                controlPublication);
        }

        state = State.INACTIVE;
    }

    public boolean isDone()
    {
        return state == State.INACTIVE;
    }

    State state()
    {
        return state;
    }

    void setThreadLocalControlSessionProxy(final ControlSessionProxy proxy)
    {
        threadLocalControlSessionProxy = proxy;
    }

    private int replay()
    {
        try
        {
            final int polled = cursor.controlledPoll(fragmentPoller, REPLAY_BATCH_SIZE);
            if (cursor.isDone())
            {
                lingerSinceMs = epochClock.time();
                state = State.LINGER;
            }

            return polled;
        }
        catch (final Exception ex)
        {
            return closeOnError(ex, "Cursor read failed");
        }
    }

    private boolean onFragment(final UnsafeBuffer termBuffer, final int offset, final int length)
    {
        if (isDone())
        {
            return false;
        }

        final int frameOffset = offset - DataHeaderFlyweight.HEADER_LENGTH;
        final int frameType = frameType(termBuffer, frameOffset);

        final long result = frameType == FrameDescriptor.PADDING_FRAME_TYPE ?
            replayPublication.appendPadding(length) :
            replayFrame(termBuffer, offset, length, frameOffset);

        if (result > 0)
        {
            return true;
        }
        else if (result == Publication.CLOSED || result == Publication.NOT_CONNECTED)
        {
            closeOnError(null, "Replay stream has been shutdown mid-replay");
        }

        return false;
    }

    private long replayFrame(final UnsafeBuffer termBuffer, final int offset, final int length, final int frameOffset)
    {
        final long result = replayPublication.tryClaim(length, bufferClaim);
        if (result > 0)
        {
            try
            {
                bufferClaim
                    .flags(frameFlags(termBuffer, frameOffset))
                    .reservedValue(termBuffer.getLong(frameOffset + RESERVED_VALUE_OFFSET, LITTLE_ENDIAN))
                    .buffer().putBytes(bufferClaim.offset(), termBuffer, offset, length);
            }
            finally
            {
                bufferClaim.commit();
            }
        }

        return result;
    }

    private int linger()
    {
        if (hasLingered() || !replayPublication.isConnected())
        {
            state = State.INACTIVE;
        }

        return 0;
    }

    private boolean hasLingered()
    {
        return epochClock.time() - LINGER_LENGTH_MS > lingerSinceMs;
    }

    private int init()
    {
        if (!replayPublication.isConnected())
        {
            if (hasLingered())
            {
                return closeOnError(null, "No connection established for replay");
            }

            return 0;
        }

        threadLocalControlSessionProxy.sendReplayStarted(correlationId, replaySessionId, controlPublication);
        state = State.REPLAY;

        return 1;
    }

    private int closeOnError(final Throwable ex, final String errorMessage)
    {
        state = State.INACTIVE;
        if (controlPublication.isConnected())
        {
            threadLocalControlSessionProxy.sendResponse(
                correlationId,
                ControlResponseCode.ERROR,
                errorMessage,
                controlPublication);
        }

        if (ex != null)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return 0;
    }
}
