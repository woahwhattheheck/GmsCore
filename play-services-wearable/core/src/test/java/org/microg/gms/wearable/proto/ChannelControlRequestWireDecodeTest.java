/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.wearable.proto;

import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression fixture for the {@link ChannelControlRequest} wire decoder and the
 * fixed-width channel IDs used by the Wearable channel protocol.
 *
 * <p>Schema under test (play-services-wearable/core/src/main/proto/wearable.proto):
 * {@code optional int32 type = 1} and {@code optional sfixed64 channelId = 2}.
 * The generated adapter binds field 2 to {@code ProtoAdapter.SFIXED64}, i.e. wire type
 * FIXED64 / I64, tag {@code (2 << 3) | 1 = 0x11}, followed by an 8-byte little-endian
 * payload.
 *
 * <p>All byte arrays in this test are CONSTRUCTED fixtures assembled field-by-field in
 * the helpers below. They are not captured device traffic; the March trace referenced
 * by the issue is unavailable, so these fixtures only pin the decoder's contract for
 * the documented wire encodings.
 */
public class ChannelControlRequestWireDecodeTest {

    private static final int TYPE_OPEN = 1;     // ChannelControlRequest.Type.CHANNEL_CONTROL_OPEN
    private static final int TYPE_OPEN_ACK = 2; // ChannelControlRequest.Type.CHANNEL_CONTROL_OPEN_ACK
    private static final int TYPE_CLOSE = 3;    // ChannelControlRequest.Type.CHANNEL_CONTROL_CLOSE

    private static final long CHANNEL_ID = 0x0102030405060708L;

    @Test
    public void decodesOpenWithSfixed64ChannelId() throws IOException {
        // CONSTRUCTED: field 1 VARINT type=OPEN, field 2 I64 (tag 0x11) channelId.
        byte[] bytes = concat(varintField(1, TYPE_OPEN), fixed64Field(2, CHANNEL_ID));
        ChannelControlRequest decoded = ChannelControlRequest.ADAPTER.decode(bytes);
        assertEquals(Integer.valueOf(TYPE_OPEN), decoded.type);
        assertEquals(Long.valueOf(CHANNEL_ID), decoded.channelId);
    }

    @Test
    public void decodesOpenAckWithSfixed64ChannelId() throws IOException {
        // CONSTRUCTED: field 1 VARINT type=OPEN_ACK, field 2 I64 (tag 0x11) channelId.
        byte[] bytes = concat(varintField(1, TYPE_OPEN_ACK), fixed64Field(2, CHANNEL_ID));
        ChannelControlRequest decoded = ChannelControlRequest.ADAPTER.decode(bytes);
        assertEquals(Integer.valueOf(TYPE_OPEN_ACK), decoded.type);
        assertEquals(Long.valueOf(CHANNEL_ID), decoded.channelId);
    }

    @Test
    public void decodesCloseWithSfixed64ChannelId() throws IOException {
        // CONSTRUCTED: field 1 VARINT type=CLOSE, field 2 I64 (tag 0x11) channelId,
        // field 7 VARINT closeErrorCode.
        byte[] bytes = concat(varintField(1, TYPE_CLOSE), fixed64Field(2, CHANNEL_ID), varintField(7, 0));
        ChannelControlRequest decoded = ChannelControlRequest.ADAPTER.decode(bytes);
        assertEquals(Integer.valueOf(TYPE_CLOSE), decoded.type);
        assertEquals(Long.valueOf(CHANNEL_ID), decoded.channelId);
        assertEquals(Integer.valueOf(0), decoded.closeErrorCode);
    }

    @Test
    public void constructedFixtureMatchesRealEncoderOutput() {
        // Proves the constructed bytes are byte-identical to what the project's own
        // Wire encoder produces, so the decode fixtures exercise the real wire form.
        ChannelControlRequest built = new ChannelControlRequest.Builder()
                .type(TYPE_OPEN)
                .channelId(CHANNEL_ID)
                .build();
        byte[] encoded = ChannelControlRequest.ADAPTER.encode(built);
        byte[] constructed = concat(varintField(1, TYPE_OPEN), fixed64Field(2, CHANNEL_ID));
        assertTrue("constructed fixture bytes differ from encoder output",
                Arrays.equals(encoded, constructed));
    }

    @Test
    public void decodesThroughNestedChannelRequestPath() throws IOException {
        // CONSTRUCTED: the real routing path — RootMessage.channelRequest (field 16) ->
        // Request.request (field 9) -> ChannelRequest.channelControlRequest (field 2) ->
        // ChannelControlRequest. Mirrors ChannelManager.onChannelRequestReceived.
        byte[] control = concat(varintField(1, TYPE_OPEN), fixed64Field(2, CHANNEL_ID));
        byte[] channelRequest = lengthDelimitedField(2, control);
        byte[] request = lengthDelimitedField(9, channelRequest);
        byte[] root = lengthDelimitedField(16, request);

        RootMessage decodedRoot = RootMessage.ADAPTER.decode(root);
        assertNotNull(decodedRoot.channelRequest);
        assertNotNull(decodedRoot.channelRequest.request);
        ChannelControlRequest ctrl = decodedRoot.channelRequest.request.channelControlRequest;
        assertNotNull(ctrl);
        assertEquals(Integer.valueOf(TYPE_OPEN), ctrl.type);
        assertEquals(Long.valueOf(CHANNEL_ID), ctrl.channelId);
    }

    @Test
    public void rejectsVarintWireTypeForChannelId() {
        // CONSTRUCTED wrong-wire fixture: field 2 presented as VARINT (tag 0x10, the tag
        // an int64 channelId would use) with a varint payload. The sfixed64 decoder must
        // not silently accept it. This is a constructed negative wire-format control,
        // not a reconstruction of the historical failure's unknown field or payload.
        byte[] bytes = concat(varintField(1, TYPE_OPEN), new byte[]{0x10}, varint(42));
        // Verified on the real 4.9.9 decoder: wire-type mismatch surfaces as
        // ProtocolException("Expected FIXED64 or LENGTH_DELIMITED but was 0").
        expectDecodeFailure(bytes, ProtocolException.class);
    }

    @Test
    public void rejectsTruncatedSfixed64ChannelId() {
        // CONSTRUCTED truncated fixture: field 2 I64 tag (0x11) but only 3 payload bytes
        // where 8 are required. Verified on the real decoder: EOFException.
        byte[] bytes = concat(varintField(1, TYPE_OPEN), new byte[]{0x11, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC});
        expectDecodeFailure(bytes, EOFException.class);
    }

    private static void expectDecodeFailure(byte[] bytes, Class<? extends IOException> expectedType) {
        try {
            ChannelControlRequest.ADAPTER.decode(bytes);
            fail("decoder accepted malformed fixture: " + toHex(bytes));
        } catch (IOException thrown) {
            assertTrue("expected " + expectedType.getName() + " but got "
                            + thrown.getClass().getName() + ": " + thrown.getMessage(),
                    expectedType.isInstance(thrown));
        }
    }

    private static byte[] varintField(int fieldNumber, long value) {
        return concat(tag(fieldNumber, 0), varint(value));
    }

    private static byte[] fixed64Field(int fieldNumber, long value) {
        byte[] tag = tag(fieldNumber, 1);
        byte[] out = new byte[tag.length + 8];
        System.arraycopy(tag, 0, out, 0, tag.length);
        for (int i = 0; i < 8; i++) {
            out[tag.length + i] = (byte) ((value >>> (8 * i)) & 0xFF);
        }
        return Arrays.copyOf(out, tag.length + 8);
    }

    private static byte[] lengthDelimitedField(int fieldNumber, byte[] payload) {
        return concat(tag(fieldNumber, 2), varint(payload.length), payload);
    }

    private static byte[] tag(int fieldNumber, int wireType) {
        return varint((fieldNumber << 3) | wireType);
    }

    private static byte[] varint(long value) {
        byte[] buf = new byte[10];
        int i = 0;
        while (true) {
            int bits = (int) (value & 0x7F);
            value >>>= 7;
            if (value == 0) {
                buf[i++] = (byte) bits;
                return Arrays.copyOf(buf, i);
            }
            buf[i++] = (byte) (bits | 0x80);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
