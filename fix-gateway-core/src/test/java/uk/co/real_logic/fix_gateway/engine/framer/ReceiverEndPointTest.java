/*
 * Copyright 2015 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.engine.framer;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import uk.co.real_logic.agrona.LangUtil;
import uk.co.real_logic.agrona.concurrent.AtomicBuffer;
import uk.co.real_logic.agrona.concurrent.AtomicCounter;
import uk.co.real_logic.fix_gateway.messages.MessageStatus;
import uk.co.real_logic.fix_gateway.replication.GatewayPublication;
import uk.co.real_logic.fix_gateway.session.SessionIdStrategy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.function.ToIntFunction;

import static org.junit.Assert.assertFalse;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.fix_gateway.library.session.Session.UNKNOWN;
import static uk.co.real_logic.fix_gateway.messages.MessageStatus.INVALID_CHECKSUM;
import static uk.co.real_logic.fix_gateway.messages.MessageStatus.OK;
import static uk.co.real_logic.fix_gateway.util.TestMessages.*;

public class ReceiverEndPointTest
{
    private static final int MESSAGE_TYPE = 'D';
    private static final long CONNECTION_ID = 20L;
    private static final long SESSION_ID = 4L;

    private SocketChannel mockChannel = mock(SocketChannel.class);
    private GatewayPublication mockPublication = mock(GatewayPublication.class);
    private SessionIdStrategy mockSessionIdStrategy = mock(SessionIdStrategy.class);
    private SessionIds mockSessionIds = mock(SessionIds.class);
    private AtomicCounter messagesRead = mock(AtomicCounter.class);

    private ReceiverEndPoint endPoint =
        new ReceiverEndPoint(
            mockChannel, 16 * 1024, mockPublication, CONNECTION_ID, UNKNOWN, mockSessionIdStrategy, mockSessionIds,
            messagesRead);

    @Before
    public void setUp()
    {
        when(mockSessionIds.onLogon(any())).thenReturn(SESSION_ID);
    }

    @Test
    public void shouldHandleValidFixMessageInOneGo()
    {
        given:
        theEndpointReceivesACompleteMessage();

        when:
        endPoint.receiveData();

        then:
        handlerReceivesAFramedMessage();
    }

    @Test
    public void shouldIgnoreGarbledMessages() throws IOException
    {
        given:
        theEndpointReceives(GARBLED_MESSAGE, 0, GARBLED_MESSAGE.length);

        when:
        endPoint.receiveData();

        then:
        handlerNotCalled();
        assertFalse("Endpoint Disconnected", endPoint.hasDisconnected());
    }

    @Ignore
    @Test
    public void shouldOnlyFrameCompleteFixMessage()
    {
        given:
        theEndpointReceivesAnIncompleteMessage();

        when:
        endPoint.receiveData();

        then:
        handlerNotCalled();
    }

    @Test
    public void shouldFrameSplitFixMessage()
    {
        given:
        theEndpointReceivesAnIncompleteMessage();
        endPoint.receiveData();

        when:
        theEndpointReceivesTheRestOfTheMessage();
        endPoint.receiveData();

        then:
        handlerReceivesAFramedMessage();
    }

    @Test
    public void shouldFrameTwoCompleteFixMessagesInOnePacket()
    {
        given:
        theEndpointReceivesTwoCompleteMessages();

        when:
        endPoint.receiveData();

        then:
        handlerReceivesTwoFramedMessages();
    }

    @Test
    public void shouldFrameOneCompleteMessageWhenTheSecondMessageIsIncomplete()
    {
        given:
        theEndpointReceivesACompleteAndAnIncompleteMessage();

        when:
        endPoint.receiveData();

        then:
        handlerReceivesAFramedMessage();
    }

    @Test
    public void shouldFrameSecondSplitMessage()
    {
        given:
        theEndpointReceivesACompleteAndAnIncompleteMessage();
        endPoint.receiveData();

        when:
        theEndpointReceivesTheRestOfTheMessage();
        endPoint.receiveData();

        then:
        handlerReceivesFramedMessages(2, OK, MSG_LEN);
    }

    @Test
    public void aClosedSocketSavesItsDisconnect() throws IOException
    {
        given:
        theChannelIsClosedByException();

        when:
        endPoint.receiveData();

        then:
        verify(mockSessionIds).onDisconnect(anyLong());
        assertSavesDisconnect();
    }

    @Test
    public void anUnreadableSocketDisconnectsItsSession() throws IOException
    {
        given:
        theChannelIsClosed();

        when:
        endPoint.receiveData();

        then:
        assertSavesDisconnect();
    }

    @Test
    public void invalidChecksumMessageRecorded() throws IOException
    {
        theEndpointReceives(INVALID_CHECKSUM_MSG, 0, INVALID_CHECKSUM_LEN);

        endPoint.receiveData();

        verify(mockPublication, times(1))
            .saveMessage(
                any(AtomicBuffer.class), eq(0), eq(INVALID_CHECKSUM_LEN), eq(MESSAGE_TYPE), anyLong(), eq(CONNECTION_ID),
                    eq(INVALID_CHECKSUM));
    }

    // TODO: log not authenticated messages

    private void assertSavesDisconnect()
    {
        verify(mockPublication).saveDisconnect(CONNECTION_ID);
    }

    private void theChannelIsClosed() throws IOException
    {
        when(mockChannel.read(any(ByteBuffer.class))).thenReturn(-1);
    }

    private void theChannelIsClosedByException() throws IOException
    {
        doThrow(new ClosedChannelException()).when(mockChannel).read(any(ByteBuffer.class));
    }

    private void handlerReceivesAFramedMessage()
    {
        handlerReceivesFramedMessages(1, OK, MSG_LEN);
    }

    private void handlerReceivesFramedMessages(int numberOfMessages,
                                               final MessageStatus status,
                                               final int msgLen)
    {
        verify(mockPublication, times(numberOfMessages))
            .saveMessage(
                any(AtomicBuffer.class), eq(0), eq(msgLen), eq(MESSAGE_TYPE), eq(SESSION_ID), eq(CONNECTION_ID),
                    eq(status));
    }

    private void handlerReceivesTwoFramedMessages()
    {
        final InOrder inOrder = Mockito.inOrder(mockPublication);
        inOrder.verify(mockPublication, times(1))
            .saveMessage(
                any(AtomicBuffer.class), eq(0), eq(MSG_LEN), eq(MESSAGE_TYPE), eq(SESSION_ID), eq(CONNECTION_ID),
                    eq(OK));
        inOrder.verify(mockPublication, times(1))
            .saveMessage(
                any(AtomicBuffer.class), eq(MSG_LEN), eq(MSG_LEN), eq(MESSAGE_TYPE), eq(SESSION_ID), eq(CONNECTION_ID),
                    eq(OK));
        inOrder.verifyNoMoreInteractions();
    }

    private void handlerNotCalled()
    {
        verifyNoMoreInteractions(mockPublication);
    }

    private void theEndpointReceivesACompleteMessage()
    {
        theEndpointReceives(EG_MESSAGE, 0, MSG_LEN);
    }

    private void theEndpointReceivesTwoCompleteMessages()
    {
        theEndpointReceivesTwoMessages(0, MSG_LEN);
    }

    private void theEndpointReceivesAnIncompleteMessage()
    {
        theEndpointReceives(EG_MESSAGE, 0, MSG_LEN - 8);
    }

    private void theEndpointReceivesTheRestOfTheMessage()
    {
        theEndpointReceives(EG_MESSAGE, MSG_LEN - 8, 8);
    }

    private void theEndpointReceivesACompleteAndAnIncompleteMessage()
    {
        theEndpointReceivesTwoMessages(0, MSG_LEN - 8);
    }

    private void theEndpointReceives(final byte[] data, final int offset, final int length)
    {
        endpointBufferUpdatedWith(
            (buffer) ->
            {
                buffer.put(data, offset, length);
                return length;
            });
    }

    private void theEndpointReceivesTwoMessages(final int secondOffset, final int secondLength)
    {
        endpointBufferUpdatedWith(
            (buffer) -> {
                buffer.put(EG_MESSAGE)
                    .put(EG_MESSAGE, secondOffset, secondLength);
                return MSG_LEN + secondLength;
            });
    }

    private void endpointBufferUpdatedWith(final ToIntFunction<ByteBuffer> bufferUpdater)
    {
        try
        {
            doAnswer(
                (invocation) -> {
                    final ByteBuffer buffer = (ByteBuffer)invocation.getArguments()[0];
                    return bufferUpdater.applyAsInt(buffer);
                }).when(mockChannel).read(any(ByteBuffer.class));
        }
        catch (final IOException ex)
        {
            // Should never happen, test in error
            LangUtil.rethrowUnchecked(ex);
        }
    }
}